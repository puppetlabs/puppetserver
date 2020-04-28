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
        if compile_options.dig('options', 'bolt')
          require 'bolt'
          require 'bolt/target'
          require 'bolt/apply_inventory'
          require 'bolt/apply_target'
          require 'bolt/apply_result'
          require 'addressable'

          Puppet[:rich_data] = true
          Puppet[:node_name_value] = compile_options['certname']
          json_deserialized_facts = JSON.parse(compile_options['facts']['values'])
          json_deserialized_trusted_facts = JSON.parse(compile_options['trusted_facts']['values'])
          json_deserialzed_vars = JSON.parse(compile_options['variables']['values'])
          Puppet.warning("boltib_path: #{boltlib_path}")
          # Use the existing environment with the requested name
          Puppet::Pal.in_environment(compile_options['environment'],
                                     pre_modulepath: boltlib_path,
                                     envpath: Puppet[:environmentpath],
                                     facts: json_deserialized_facts) do |pal|
            # TODO: We need to decide on an appropriate config for inventory. Given we 
            # hide this from plan authors this current iteration has only the "required"
            # data for now. This is being discussed on https://github.com/puppetlabs/bolt/pull/1770
            fake_config = {
              'transport' => 'redacted',
              'transports' => {
                'redacted' => 'redacted'
              }
            }
            bolt_inv = Bolt::ApplyInventory.new(fake_config)
            Puppet.override(bolt_inventory: bolt_inv) do
              Puppet.lookup(:pal_current_node).trusted_data = json_deserialized_trusted_facts
              # This compiler has been configured with a node containing
              # the requested environment, facts, and variables, and is used
              # to compile a catalog in that context from the supplied AST.
              pal.with_catalog_compiler do |compiler|
                #TODO: Should we update these in PAL? They are the defaults in Puppet
                Puppet[:strict] = :warning
                Puppet[:strict_variables] = false
                vars = Puppet::Pops::Serialization::FromDataConverter.convert(json_deserialzed_vars)

                json_deserialized_facts.keys.each {|fact_name| vars.delete(fact_name)}
                # TODO: Refactor PAL api such that we do not need to use private methods
                pal.send(:add_variables, compiler.send(:topscope), vars)

                # We have to parse the AST inside the compiler block, because it
                # initializes the necessary type loaders for us.
                ast = Puppet::Pops::Serialization::FromDataConverter.convert(code)
                # Node definitions must be at the top level of the apply block.
                # That means the apply body either a) consists of just a
                # NodeDefinition, b) consists of a BlockExpression which may
                # contain NodeDefinitions, or c) doesn't contain NodeDefinitions.
                definitions = if ast.is_a?(Puppet::Pops::Model::BlockExpression)
                                ast.statements.select { |st| st.is_a?(Puppet::Pops::Model::NodeDefinition) }
                              elsif ast.is_a?(Puppet::Pops::Model::NodeDefinition)
                                [ast]
                              else
                                []
                              end
                ast = Puppet::Pops::Model::Factory.PROGRAM(ast, definitions, ast.locator).model

                compiler.evaluate(ast)
                compiler.instance_variable_get(:@internal_compiler).send(:evaluate_ast_node)
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
    end
  end
end
