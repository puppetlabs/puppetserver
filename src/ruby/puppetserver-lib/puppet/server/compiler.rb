require 'puppet/server'
require 'puppet/server/logging'

module Puppet
  module Server
    class Compiler

      def initialize
        set_server_facts
      end

      # Compiles a catalog according to the spec provided from the
      # request.
      # @param [Hash] request_data details about the catalog to be compiled
      # @return [Hash] containing either just the catalog or catalog and logs,
      #                if capturing logs was enabled
      def compile(request_data)
        options = request_data['options'] || {}

        if options['capture_logs']
          catalog, logs = Logging.capture_logs(options['log_level']) do
            compile_catalog(request_data)
          end

          { catalog: catalog, logs: logs }
        else
          catalog = compile_catalog(request_data)
          { catalog: catalog }
        end
      end

      # Make the node object to be used in compilation. This requests it from
      # the node indirection and merges local facts and other data.
      # @api private
      def create_node(request_data)
        facts, trusted_facts = process_facts(request_data)
        certname = request_data['certname']
        requested_environment = request_data['environment']
        transaction_uuid = request_data['transaction_uuid']
        prefer_requested_environment =
          request_data.dig('options', 'prefer_requested_environment')


        node = Puppet.override(trusted_information: trusted_facts) do
          Puppet::Node.indirection.find(certname,
                                        environment: requested_environment,
                                        facts: facts,
                                        transaction_uuid: transaction_uuid)
        end

        if request_data['facts'].nil? && !prefer_requested_environment
          tries = 0
          environment = requested_environment
          while node.environment != environment
            if tries > 3
              raise Puppet::Error, _("Node environment didn't stabilize after %{tries} fetches, aborting run") % { tries: tries }
            end

            environment = node.environment
            facts = get_facts_from_pdb(certname, environment.to_s)
            node = Puppet.override(trusted_information: trusted_facts) do
              Puppet::Node.indirection.find(certname,
                                            environment: environment,
                                            configured_environment: requested_environment,
                                            facts: facts,
                                            transaction_uuid: transaction_uuid)
            end
            tries += 1
          end
        end

        if prefer_requested_environment
          node.environment = requested_environment
        end

        node.trusted_data = trusted_facts
        node.add_server_facts(@server_facts)
        node
      end

      private

      def compile_catalog(request_data)
        persist = request_data['persistence']
        save_options = request_data.slice('environment',
                                          'transaction_id',
                                          'certname',
                                          'job_id')

        node = create_node(request_data)

        if persist['facts']
          save_facts(node.facts, node.trusted_data, save_options)
        end

        # Note: if we change this to use the indirection we may no longer
        # need to call `save_catalog` below. See its documentation for
        # further info.
        catalog = Puppet::Parser::Compiler.compile(node, request_data['code_id'])

        if persist['catalog']
          save_catalog(catalog, save_options)
        end

        catalog.to_data_hash
      end

      # Typically in our use case (~early 2019, with PuppetDB configured as
      # the primary terminus and yaml as the cache) Indirection.save will
      # save to both the primary terminus and cache.
      #
      # @param [Puppet::Node::Facts] facts
      # @param [Hash] trusted_facts
      # @param [Hash] options
      # @option options [String] environment    Required
      # @option options [String] certname       Required
      def save_facts(facts, trusted_facts, options)
        # trusted_facts are pulled from the context in at least the PDB terminus.
        Puppet.override({trusted_information: trusted_facts}) do
          Puppet::Node::Facts.indirection.save(facts, options.delete('certname'), options)
        end
      end

      # The current Compiler terminus (which is the primary terminus for the
      # Catalog indirection) does not implement save and so we
      # cannot call Indirection#save directly. Typically, the catalog is
      # "saved" to PDB because PDB becomes the cache terminus and
      # Indirection#find will attempt to cache on successful lookup.
      #
      # Should we begin retrieving the catalog via Indirection#find this
      # method may become unnecessary
      #
      # @param [Puppet::Resource::Catalog] catalog
      # @param [Hash] options
      # @option options [String] environment    Required
      # @option options [String] certname       Required
      # @option options [String] transaction_id Optional
      # @option options [String] job_id         Optional
      def save_catalog(catalog, options)
        if Puppet::Resource::Catalog.indirection.cache?
          terminus = Puppet::Resource::Catalog.indirection.cache

          request = Puppet::Indirector::Request.new(terminus.class.name,
                                                    :save,
                                                    options.delete('certname'),
                                                    catalog,
                                                    options)

          terminus.save(request)
        end
      end

      # @return Puppet::Node::Facts facts, Hash trusted_facts
      def process_facts(request_data)
        facts = extract_facts(request_data)
        trusted_facts = extract_trusted_facts(request_data, facts)

        return facts, trusted_facts
      end

      def extract_facts(request_data)
        if request_data['facts'].nil?
          if Puppet::Node::Facts.indirection.terminus.name.to_s == "puppetdb"
            facts = get_facts_from_pdb(request_data['certname'], request_data['environment'])
          else
            raise(Puppet::Error, "PuppetDB not configured, please provide facts with your catalog request.")
          end
        else
          facts_from_request = request_data['facts']

          # Ensure request data has the proper keys, mirroring the structure
          # of Facts#to_data_hash
          facts_from_request['values'] ||= {}
          facts_from_request['name'] ||= request_data['certname']

          facts = Puppet::Node::Facts.from_data_hash(facts_from_request)
        end

        facts.sanitize

        facts
      end

      def extract_trusted_facts(request_data, facts)
        # Pull the trusted facts from the request, or attempt to extract them from
        # the facts hash
        trusted_facts = if request_data['trusted_facts']
                          request_data['trusted_facts']['values']
                        else
                          fact_values = facts.to_data_hash['values']
                          fact_values['trusted']
                        end
        # If no trusted facts could be found, ensure a hash is returned
        trusted_facts ||= {}
      end

      def get_facts_from_pdb(nodename, environment)
        pdb_terminus = Puppet::Node::Facts::Puppetdb.new
        request = Puppet::Indirector::Request.new(pdb_terminus.class.name,
                                                  :find,
                                                  nodename,
                                                  nil,
                                                  :environment => environment)
        facts = pdb_terminus.find(request)

        # If no facts have been stored for the node, PDB will return nil
        if facts.nil?
          # Create an empty facts object
          facts = Puppet::Node::Facts.new(nodename)
        end

        facts
      end

      # Initialize our server fact hash; we add these to each catalog, and they
      # won't change while we're running, so it's safe to cache the values.
      #
      # See also set_server_facts in Puppet::Indirector::Catalog::Compiler in puppet.
      def set_server_facts
        @server_facts = {}

        # Add our server Puppet Enterprise version, if available.
        pe_version_file = '/opt/puppetlabs/server/pe_version'
        if File.readable?(pe_version_file) and !File.zero?(pe_version_file)
          @server_facts['pe_serverversion'] = File.read(pe_version_file).chomp
        end

        # Add our server version to the fact list
        @server_facts['serverversion'] = Puppet.version.to_s

        # And then add the server name and IP
        { 'servername' => 'fqdn',
          'serverip' => 'ipaddress',
          'serverip6' => 'ipaddress6'
        }.each do |var, fact|
          value = Facter.value(fact)
          if value
            @server_facts[var] = value
          end
        end

        if @server_facts['servername'].nil?
          host = Facter.value(:hostname)
          if host.nil?
            Puppet.warning _("Could not retrieve fact servername")
          elsif domain = Facter.value(:domain)
            @server_facts['servername'] = [host, domain].join('.')
          else
            @server_facts['servername'] = host
          end
        end

        if @server_facts['serverip'].nil? && @server_facts['serverip6'].nil?
          Puppet.warning _("Could not retrieve either serverip or serverip6 fact")
        end
      end
    end
  end
end
