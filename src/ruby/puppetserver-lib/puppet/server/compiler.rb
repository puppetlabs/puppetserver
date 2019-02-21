require 'puppet/server'

module Puppet
  module Server
    class Compiler

      def initialize
        set_server_facts
      end

      def compile(request_data)
        processed_hash = convert_java_args_to_ruby(request_data)

        node = create_node(processed_hash)

        catalog = Puppet::Parser::Compiler.compile(node, processed_hash['job_id'])
        catalog.to_data_hash
      end

      private

      def create_node(request_data)
        # We need an environment to talk to PDB
        request_data['environment'] ||= 'production'

        facts, trusted_facts = process_facts(request_data)
        node_params = { facts: facts,
                        # TODO: fetch environment from classifier
                        environment: request_data['environment'],
                        # data added to the node object and exposed in manifests as
                        # top-level vars. Maybe related to class params??
                        # Can these also come from the classifier?
                        parameters: request_data['parameters'],
                        # TODO: fetch classes from classifier
                        classes: request_data['classes'] }

        node = Puppet::Node.new(request_data['certname'], node_params)
        # Merges facts into the node parameters.
        # Ensures that facts will be surfaced as top-scope variables,
        # along with other node parameters.
        node.merge(facts.values)
        node.trusted_data = trusted_facts
        node.add_server_facts(@server_facts)
        node
      end

      def convert_java_args_to_ruby(hash)
        Hash[hash.collect do |key, value|
            # Stolen and modified from params_to_ruby in handler.rb
            newkey = key.to_s
            # Java::ClojureLang::Keywords retain their leading colon when
            # converted to a string
            newkey.delete_prefix!(':')

            if value.java_kind_of?(Java::ClojureLang::IPersistentMap)
              [newkey, convert_java_args_to_ruby(value)]
            elsif value.java_kind_of?(Java::JavaUtil::List)
              [newkey, value.to_a]
            else
              [newkey, value]
            end
          end]
      end


      # @return Puppet::Node::Facts facts, Hash trusted_facts
      def process_facts(request_data)
        facts = extract_facts(request_data)
        trusted_facts = extract_trusted_facts(request_data, facts)

        return facts, trusted_facts
      end

      def extract_facts(request_data)
        if request_data['facts'].nil?
          if Puppet[:storeconfigs] == true
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
