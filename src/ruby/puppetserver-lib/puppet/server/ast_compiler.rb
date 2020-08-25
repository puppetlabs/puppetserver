require 'puppet_pal'
require 'puppet/server/log_collector'
require 'puppet/server/logging'

module Puppet
  module Server
    class ASTCompiler
      def self.compile(compile_options, boltlib_path)
        options = compile_options['options'] || {}

        log_level = options['log_level']
        code = JSON.parse(compile_options['code_ast'])

        if options['capture_logs']
          catalog, logs = Logging.capture_logs(log_level) do
            compile_ast(code, compile_options, boltlib_path)
          end

          { catalog: catalog, logs: logs }
        else
          catalog = compile_ast(code, compile_options, boltlib_path)
          { catalog: catalog }
        end
      end

      def self.compile_ast(code, compile_options, boltlib_path)
        # If the request requires that bolt be loaded we assume we are in a properly 
        # configured PE environment
        if compile_options.dig('options', 'compile_for_plan')
          unless boltlib_path
            msg = "When compile_for_plan is set, the path to boltlib modules " \
            "must be provided by setting boltlib-path as a jruby-puppet setting in pe-puppet-server.conf"
            raise(Puppet::Error, msg)
          end

          # TODO: PE-28677 Develop entrypoint for loading bits of bolt we need here.
          require 'bolt/apply_inventory'
          require 'bolt/apply_target'
          require 'bolt/pal/issues'

          Puppet[:node_name_value] = compile_options['certname']

          # Prior to PE-29443 variables were in a hash. Serialization between ruby/clojure/json did
          # not preserver hash order necessary for deserialization. The data strucutre is now stored
          # in a list for moving data and the list is used to construct an ordered ruby hash.
          variables = if compile_options['variables']['values'].is_a?(Array)
                        compile_options['variables']['values'].each_with_object({}) do |param_hash, acc|
                          acc[param_hash.keys.first] = param_hash.values.first
                        end
                      else
                        compile_options['variables']['values']
                      end

          env_conf = {
            pre_modulepath: boltlib_path,
            envpath: Puppet[:environmentpath],
            facts: compile_options['facts']['values'],
            variables: variables
          }

          # Use the existing environment with the requested name
          Puppet::Pal.in_environment(compile_options['environment'], env_conf) do |pal|
            # TODO: Given we hide this from plan authors this current iteration has only
            # the "required" data for now. Once we can get https://github.com/puppetlabs/bolt/pull/1770
            # merged and promoted we can just use an empty hash.
            fake_config = {
              'transport' => 'redacted',
              'transports' => {
                'redacted' => 'redacted'
              }
            }
            bolt_inv = Bolt::ApplyInventory.new(fake_config)
            Puppet.override(bolt_inventory: bolt_inv) do
              Puppet.lookup(:pal_current_node).trusted_data = compile_options['trusted_facts']['values']
              # This compiler has been configured with a node containing
              # the requested environment, facts, and variables, and is used
              # to compile a catalog in that context from the supplied AST.
              pal.with_catalog_compiler() do |compiler|
                # TODO: PUP-10476 Explore setting these as default in PAL. They are the defaults in Puppet
                Puppet[:strict] = :warning
                Puppet[:strict_variables] = false

                ast = build_program(code)
                compiler.evaluate(ast)
                compiler.evaluate_ast_node
                compiler.compile_additions
                compiler.catalog_data_hash
              end
            end
          end
        else
          # When we do not need to load bolt we assume there are no Bolt types to resolve in
          # AST compilation.
          Puppet[:node_name_value] = compile_options['certname']

          # Use the existing environment with the requested name
          Puppet::Pal.in_environment(compile_options['environment'],
                                     envpath: Puppet[:environmentpath],
                                     facts: compile_options['facts']['values'],
                                     variables: compile_options['variables']['values']) do |pal|
            Puppet.lookup(:pal_current_node).trusted_data = compile_options['trusted_facts']['values']

            # This compiler has been configured with a node containing
            # the requested environment, facts, and variables, and is used
            # to compile a catalog in that context from the supplied AST.
            pal.with_catalog_compiler do |compiler|
              # We have to parse the AST inside the compiler block, because it
              # initializes the necessary type loaders for us.
              ast = Puppet::Pops::Serialization::FromDataConverter.convert(code)

              compiler.evaluate(ast)
              compiler.compile_additions
              compiler.catalog_data_hash
            end
          end
        end
      end
      private_class_method :compile_ast

      def self.build_program(code)
        ast = Puppet::Pops::Serialization::FromDataConverter.convert(code)
        # Node definitions must be at the top level of the apply block.
        # That means the apply body either a) consists of just a
        # NodeDefinition, b) consists of a BlockExpression which may
        # contain NodeDefinitions, or c) doesn't contain NodeDefinitions.
        # See https://github.com/puppetlabs/bolt/pull/1512 for more details
        definitions = if ast.is_a?(Puppet::Pops::Model::BlockExpression)
                        ast.statements.select { |st| st.is_a?(Puppet::Pops::Model::NodeDefinition) }
                      elsif ast.is_a?(Puppet::Pops::Model::NodeDefinition)
                        [ast]
                      else
                        []
                      end
        # During ordinary compilation, definitions are stored on the parser at
        # parse time and then added to the Program node at the root of the AST
        # before evaluation. Because the AST for an apply block has already been
        # parsed and is not a complete tree with a Program at the root level, we
        # need to rediscover the definitions and construct our own Program object.
        # https://github.com/puppetlabs/bolt/commit/3a7597dda25cdb25854c7d08d37c5c58ab6a016b
        Puppet::Pops::Model::Factory.PROGRAM(ast, definitions, ast.locator).model
      end
      private_class_method :build_program
    end
  end
end
