require 'puppet/server'

module Puppet
  module Server
    class Compiler

      def initialize
        set_server_facts
        @adapters_info = collect_adapters_info
      end

      def compile(request_data)
        processed_hash = convert_java_args_to_ruby(request_data)

        node = create_node(processed_hash)

        catalog = Puppet::Parser::Compiler.compile(node, processed_hash['job_id'])

        maybe_save(processed_hash, node.facts, catalog)

        catalog.to_data_hash
      end

      private

      def maybe_save(processed_hash, facts, catalog)
        nodename = processed_hash['certname']
        persist = processed_hash['persistence']
        options = processed_hash.
                    slice("environment", "transaction_id").
                    map {|key, val| [key.intern, val] }.to_h

        if persist['facts']
          Puppet.override({trusted_information: processed_hash['trusted_facts']}) do
            save_artifact(:facts, facts, nodename, options)
          end
        end

        if persist['catalog']
          save_artifact(:catalog, catalog, nodename, options)
        end
      end

      # Some primary termini may not implement save (like with Catalog).
      # In those cases we need to fall back to the cache class and if it
      # is unconfigured then raise.
      def save_artifact(indirection, artifact, nodename, options)
        terminus_class = @adapters_info[indirection][:actual_terminus_class]
        terminus = terminus_class ? terminus_class.new : nil

        unless terminus && terminus.respond_to?(:save)
          terminus_class = @adapters_info[indirection][:actual_cache_class]
          terminus = terminus_class ? terminus_class.new : nil
        end

        unless terminus && terminus.respond_to?(:save)
          raise Puppet::Error, "No configured termini to save #{indirection.to_s}"
        end

        request = Puppet::Indirector::Request.new(terminus.class.name,
                                                  :save,
                                                  nodename,
                                                  artifact,
                                                  options)

        terminus.save(request)
      end

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
          if value.java_kind_of?(Java::ClojureLang::IPersistentMap)
            [key, convert_java_args_to_ruby(value)]
          elsif value.java_kind_of?(Java::JavaUtil::List)
            [key, value.to_a]
          else
            [key, value]
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
          if @adapters_info[:facts][:actual_terminus_name].to_s == "puppetdb"
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
        pdb_terminus = @adapters_info[:facts][:actual_terminus_class].new
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

      def find_terminus_class(indirection, terminus_name)
        if terminus_name
          Puppet::Indirector::Terminus.terminus_class(indirection, terminus_name)
        else
          nil
        end
      end

      # The StoreConfigs indirection wraps the storeconfigs_backend.
      def maybe_deref_storeconfigs(indirection, terminus_name, terminus_class)
        if terminus_name == :store_configs
          actual_name = Puppet.settings[:storeconfigs_backend]
          actual_class =
            Puppet::Indirector::Terminus.terminus_class(indirection,
                                                        actual_name)
        else
          actual_name = terminus_name
          actual_class = terminus_class
        end

        return actual_name, actual_class
      end

      # This is broken out into its own method simply because the length
      # of comments within break the flow of `collect_adapters_info`.
      def basic_indirection_info(indirection)
        # The below is an instance of that indirected class, eg
        # `Puppet::Node::Facts.new` which contains some information about
        # its configuration
        indirection_instance = Puppet::Indirector::Indirection.instance(indirection)

        # An actual class ref of what will be indirected, eg `Puppet::Node::Facts`
        indirected_class_reference = indirection_instance.model

        # Symbol or String (eg :store_configs) or `nil` (no cache)
        # non-nil can be used to look up class ref eg
        # `Terminus.terminus_class(:catalog, :store_configs)`
        cache_terminus_name = indirection_instance.cache_class

        # The symbol, eg :compiler that can be given to
        # `Terminus.terminus_class` same as cache_class
        # May be `nil`, if so terminus_setting should be consulted
        primary_terminus_name = indirection_instance.terminus_class

        # Where to find any configuration for what default terminus to use
        # Will be a symbol that can be passed into `Puppet.setting[<here>]`
        terminus_setting = indirection_instance.terminus_setting

        return indirected_class_reference, cache_terminus_name,
          primary_terminus_name, terminus_setting
      end

      # Returns a Hash with symbol keys naming each indirection (eg :facts)
      # Each indirection key refers to a Hash with the configured termini
      # (name and class) for its primary and cache usages, if a terminus is
      # :store_configs it will find the associated terminus for
      # :storeconfigs_backend and place that in the "actual" keys for the
      # termini. If not store_configs, then the actual keys will be the
      # values in origal "configured" keys eg.
      #   {
      #     :catalog =>
      #       {:indirected_class       => Puppet::Resource::Catalog,
      #        :cache_terminus_name    => :store_configs,
      #        :actual_cache_name      => :puppetdb,
      #        :primary_terminus_name  => :compiler,
      #        :actual_terminus_name   => :compiler,
      #        :cache_terminus_class   => Puppet::Resource::Catalog::StoreConfigs,
      #        :actual_cache_class     => Puppet::Resource::Catalog::Puppetdb,
      #        :primary_terminus_class => Puppet::Resource::Catalog::Compiler,
      #        :actual_terminus_class  => Puppet::Resource::Catalog::Compiler,
      #        :terminus_setting       => :catalog_terminus},
      #     :facts => ...,
      #     ...
      #   }
      def collect_adapters_info
        adapters = {}

        # Returns an array of symbols for registered
        # adapters/indirections, e.g. :facts
        Puppet::Indirector::Indirection.instances.each do |indirection|

          indirected_class_reference, cache_terminus_name,
          primary_terminus_name, terminus_setting =
            basic_indirection_info(indirection)

          cache_terminus_class = find_terminus_class(indirection, cache_terminus_name)

          actual_cache_name, actual_cache_class =
            maybe_deref_storeconfigs(indirection,
                                       cache_terminus_name,
                                       cache_terminus_class)


          # Every indirection needs a primary terminus, cache however does not.
          primary_terminus_name ||= Puppet.settings[terminus_setting]

          primary_terminus_class = find_terminus_class(indirection, primary_terminus_name)

          actual_terminus_name, actual_terminus_class =
            maybe_deref_storeconfigs(indirection,
                                     primary_terminus_name,
                                     primary_terminus_class)

          adapters[indirection] = {
            indirected_class: indirected_class_reference,

            cache_terminus_name: cache_terminus_name,
            actual_cache_name: actual_cache_name,
            primary_terminus_name: primary_terminus_name,
            actual_terminus_name: actual_terminus_name,

            cache_terminus_class: cache_terminus_class,
            actual_cache_class: actual_cache_class,
            primary_terminus_class: primary_terminus_class,
            actual_terminus_class: actual_terminus_class,

            terminus_setting: terminus_setting,
          }
        end

        adapters
      end
    end
  end
end
