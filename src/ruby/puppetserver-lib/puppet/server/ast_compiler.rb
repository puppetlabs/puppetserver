require 'puppet_pal'
require 'puppet/server/log_collector'
require 'puppet/server/logging'

module Puppet
  module Server
    class ASTCompiler
      def self.compile(compile_options)
        options = compile_options['options'] || {}

        log_level = options['log_level']
        code = JSON.parse(compile_options['code_ast'])

        if options['capture_logs']
          catalog, logs = Logging.capture_logs(log_level) do
            compile_ast(code, compile_options)
          end

          { catalog: catalog, logs: logs }
        else
          catalog = compile_ast(code, compile_options)
          { catalog: catalog }
        end
      end

      def self.compile_ast(code, compile_options)
        # TODO: move requires to optimal place and guard against FOSS not having bolt lib code
        # CODEREVIEW: where should these requires be, and what should happen if they are not found?
        require 'bolt'
        require 'bolt/target'
        require 'bolt/apply_inventory'
        require 'bolt/apply_target'
        require 'bolt/apply_result'
        require 'addressable'

        Puppet[:rich_data] = true
        Puppet[:node_name_value] = compile_options['certname']

        # TODO: again, error handling/warning here when this is not supplied
        boltlib_path = Array(compile_options.dig('options', 'boltlib') || '')
        # Use the existing environment with the requested name
        Puppet::Pal.in_environment(compile_options['environment'],
                                   pre_modulepath: boltlib_path,
                                   envpath: Puppet[:environmentpath],
                                   facts: compile_options['facts']['values']) do |pal|
          # No conn info for PE targets can just use empty hash if 
          # https://github.com/puppetlabs/bolt/pull/1770 is accepted
          fake_config = {
            'transport' => 'redacted',
            'transports' => {
              'redacted' => 'redacted'
            }
          }
          bolt_inv = Bolt::ApplyInventory.new(fake_config)
          Puppet.override(bolt_inventory: bolt_inv) do
            Puppet.lookup(:pal_current_node).trusted_data = compile_options['trusted_facts']['values']
            #TODO: get rid of this debug logging...
            Puppet.warning("Compile Options")
            Puppet.warning("#{compile_options}")
            # This compiler has been configured with a node containing
            # the requested environment, facts, and variables, and is used
            # to compile a catalog in that context from the supplied AST.
            pal.with_catalog_compiler do |compiler|
              #CODEREVIEW: Do we need to make these configurable in the request?
              Puppet[:strict] = :warning
              Puppet[:strict_variables] = false
              vars = Puppet::Pops::Serialization::FromDataConverter.convert(compile_options['variables']['values'])

              # If fact conflicts with plan var name, fact wins, so delete plan var
              # CODEREVIEW: logging is strange for this. For vars that are overridden, that would
              # need to happen in orch logs, for facts it would happen here. Is it ok to just
              # silently overwrite?
              compile_options['facts']['values'].keys.each {|fact_name| vars.delete(fact_name)}
              Puppet.warning("FACTS KEYS #{compile_options['facts']['values'].keys}")
              pal.send(:add_variables, compiler.send(:topscope), vars)

              # We have to parse the AST inside the compiler block, because it
              # initializes the necessary type loaders for us.
              ast = Puppet::Pops::Serialization::FromDataConverter.convert(code)
              unless ast.is_a?(Puppet::Pops::Model::Program)
                Puppet.warning("AST IS A: Puppet::Pops::Model::Program")
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
              end
              compiler.evaluate(ast)
              compiler.instance_variable_get(:@internal_compiler).send(:evaluate_ast_node)
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
