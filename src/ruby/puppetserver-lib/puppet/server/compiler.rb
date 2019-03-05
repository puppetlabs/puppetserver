require 'puppet/server'
require 'puppet/server/log_collector'

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
          catalog, logs = capture_logs do
            compile_catalog(request_data)
          end

          { catalog: catalog, logs: logs }
        else
          catalog = compile_catalog(request_data)
          { catalog: catalog }
        end
      end

      # private

      def compile_catalog(request_data)
        persist = request_data['persistence']
        save_options = request_data.slice('environment', 'transaction_id', 'certname')

        node = create_node(request_data)

        if persist['facts']
          save_facts(node.facts, node.trusted_data, save_options)
        end

        # Note: if we change this to use the indirection we may no longer
        # need to call `save_catalog` below. See its documentation for
        # further info.
        catalog = Puppet::Parser::Compiler.compile(node, request_data['job_id'])

        if persist['catalog']
          save_catalog(catalog, save_options)
        end

        catalog.to_data_hash
      end

      def capture_logs(&block)
        logs = []
        result = nil
        log_dest = Puppet::Server::LogCollector.new(logs)
        Puppet::Util::Log.with_destination(log_dest) do
          result = yield
        end

        log_entries = logs.map do |log|
          log.to_data_hash
        end.select do |log|
          # Filter out debug messages, which may be verbose and
          # contain sensitive data
          log['level'] == 'warning' || log['level'] == 'error'
        end

        return result, log_entries
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

      # Make the node object to be used in compilation. This requests it from
      # the node indirection and merges local facts and other data.
      # @api private
      def create_node(request_data)
        facts, trusted_facts = process_facts(request_data)
        certname = request_data['certname']
        environment = request_data['environment'] || 'production'
        transaction_uuid = request_data['transaction_uuid']
        prefer_requested_environment =
          request_data.dig('options', 'prefer_requested_environment')

        node = Puppet::Node.indirection.find(certname,
                                             environment: environment,
                                             facts: facts,
                                             transaction_uuid: transaction_uuid)

        if prefer_requested_environment
          node.environment = environment
        end

        # Merges facts into the node parameters.
        # Ensures that facts will be surfaced as top-scope variables,
        # along with other node parameters.
        node.merge(facts.values)
        node.trusted_data = trusted_facts
        node.add_server_facts(@server_facts)
        node
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
      # This is lifted directly from Puppet::Indirector::Catalog::Compiler.
      def set_server_facts
        @server_facts = {}

        # Add our server version to the fact list
        @server_facts['serverversion'] = Puppet.version.to_s

        # And then add the server name and IP
        { 'servername' => 'fqdn',
          'serverip' => 'ipaddress'
        }.each do |var, fact|
          if value = Facter.value(fact)
            @server_facts[var] = value
          else
            Puppet.warning "Could not retrieve fact #{fact}"
          end
        end

        if @server_facts['servername'].nil?
          host = Facter.value(:hostname)
          if domain = Facter.value(:domain)
            @server_facts['servername'] = [host, domain].join('.')
          else
            @server_facts['servername'] = host
          end
        end
      end
    end
  end
end
