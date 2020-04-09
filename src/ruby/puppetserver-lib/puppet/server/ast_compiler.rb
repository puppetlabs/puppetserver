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
        require 'bolt'
        require 'bolt/target'
        Puppet[:rich_data] = true
        Puppet[:node_name_value] = compile_options['certname']

        # Use the existing environment with the requested name
        Puppet::Pal.in_environment(compile_options['environment'],
                                   pre_modulepath: ['/opt/puppetlabs/server/data/puppetserver/jruby-gems/gems/bolt-2.4.0/bolt-modules'],
                                   envpath: Puppet[:environmentpath],
                                   facts: compile_options['facts']['values']) do |pal|
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
            #TODO: Handle vars set on target object (perhaps an API change, though we may be able to munge that in requestor)
            pal.send(:add_variables, compiler.send(:topscope), vars)

            # We have to parse the AST inside the compiler block, because it
            # initializes the necessary type loaders for us.
            ast = Puppet::Pops::Serialization::FromDataConverter.convert(code)
            # Probably dont need this...
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
      private_class_method :compile_ast
    end
  end
end
