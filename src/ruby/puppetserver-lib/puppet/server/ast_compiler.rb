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
      private_class_method :compile_ast
    end
  end
end
