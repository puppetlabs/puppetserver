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
        # Save the original node_name_value, to be put back later
        original_node_name_value = Puppet[:node_name_value]
        # Set node_name_value directly. All types of compilation
        # including project/environment and plan/non-plan compiles
        # will need to have this set.
        Puppet[:node_name_value] = compile_options['certname']

        # If the request requires that bolt be loaded we assume we are in a properly
        # configured PE environment
        if compile_options.dig('options', 'compile_for_plan')
          unless boltlib_path
            msg = "When compile_for_plan is set, the path to boltlib modules " \
            "must be provided by setting boltlib-path as a jruby-puppet setting in pe-puppet-server.conf"
            raise(Puppet::Error, msg)
          end

          load_bolt()
          if compile_options.has_key?('versioned_project')
            compile_for_project_plan(code, compile_options, boltlib_path)
          else
            compile_for_environment_plan(code, compile_options, boltlib_path)
          end
        else
          compile_for_environment(code, compile_options, boltlib_path)
        end
      ensure
        Puppet[:node_name_value] = original_node_name_value
      end
      private_class_method :compile_ast

      def self.compile_for_project_plan(code, compile_options, boltlib_path)

        # Save the original hiera_config value, to be put back later
        original_hiera_config = Puppet[:hiera_config]
        # hiera_config is set directly here rather than passing it to
        # Puppet.override below. For some reason passing it to .override
        # does not correctly force it to update and compilation does not
        # correctly use the updated setting. We decided not to continue
        # pursuing debugging that behavior, and instead just set it
        # directly here and return hiera_config to it's original value
        # below.
        #                               - Sean P. McDonald 8/3/2021
        #
        Puppet[:hiera_config] = compile_options['hiera_config']

        # Modulepath needs to be combined "manually" here because there is no
        # equivalent to pre_modulepath for in_tmp_environment
        env_conf = {
          modulepath: Array(boltlib_path) + Array(compile_options['modulepath']),
          facts: compile_options['facts']['values'],
        }

        plan_variables = ordered_plan_vars(compile_options)
        target_variables = compile_options.dig('target_variables', 'values') || {}
        variables = {
          variables: plan_variables,
          target_variables: target_variables,
        }

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
        bolt_project = Struct.new(:name, :path, :load_as_module?).new(compile_options['project_name'],
                                                                      compile_options['project_root'],
                                                                      true)
        puppet_overrides = {
          bolt_inventory:  bolt_inv,
          bolt_project:    bolt_project,
        }
        Puppet::Pal.in_tmp_environment('bolt_catalog', **env_conf) do |pal|
          Puppet.override(puppet_overrides) do
            Puppet.lookup(:pal_current_node).trusted_data = compile_options['trusted_facts']['values']
            pal.with_catalog_compiler(**variables) do |compiler|
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
      ensure
        # Return hiera_config to it's original state
        Puppet[:hiera_config] = original_hiera_config
      end
      private_class_method :compile_for_project_plan

      def self.compile_for_environment_plan(code, compile_options, boltlib_path)
        plan_variables = ordered_plan_vars(compile_options)
        target_variables = compile_options.dig('target_variables', 'values') || {}
        variables = {
          variables: plan_variables,
          target_variables: target_variables,
        }

        env_conf = {
          pre_modulepath: boltlib_path,
          envpath: Puppet[:environmentpath],
          facts: compile_options['facts']['values'],
        }

        # Use the existing environment with the requested name
        Puppet::Pal.in_environment(compile_options['environment'], **env_conf) do |pal|
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
            pal.with_catalog_compiler(**variables) do |compiler|
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
      end
      private_class_method :compile_for_environment_plan

      # When we do not need to load bolt we assume there are no Bolt types to resolve in
      # AST compilation.
      def self.compile_for_environment(code, compile_options, boltlib_path)
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
      private_class_method :compile_for_environment

      # Prior to PE-29443 variables were in a hash. Serialization between ruby/clojure/json did
      # not preserve hash order necessary for deserialization. The data strucutre is now stored
      # in a list for moving data and the list is used to construct an ordered ruby hash.
      def self.ordered_plan_vars(compile_options)
        if compile_options['variables']['values'].is_a?(Array)
          compile_options['variables']['values'].each_with_object({}) do |param_hash, acc|
            acc[param_hash.keys.first] = param_hash.values.first
          end
        else
          compile_options['variables']['values']
        end
      end
      private_class_method :ordered_plan_vars

      def self.load_bolt()
        # TODO: PE-28677 Develop entrypoint for loading bits of bolt we need here.
        require 'bolt/apply_inventory'
        require 'bolt/apply_target'
        require 'bolt/pal/issues'
      end
      private_class_method :load_bolt

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
