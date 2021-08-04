require 'spec_helper'

require 'puppet_pal'

require 'puppet/server/ast_compiler'

def generate_ast(code)
  Puppet::Pal.in_tmp_environment("ast") do |pal|
    pal.with_catalog_compiler do |compiler|
      ast = compiler.parse_string(code)
      Puppet::Pops::Serialization::ToDataConverter.convert(ast,
                                                           rich_data: true,
                                                           symbol_to_string: true)
    end
  end
end

def environment_request(code)
  {"certname" => "localhost",
   "facts" => {"values" => {"my_fact" => "fact_value"}},
   "trusted_facts" => {"values" => {"my_trusted" => "trusted_value"}},
   "options" => {"capture_logs" => false},
   "environment" => "production",
   "code_ast" => generate_ast(code).to_json,
   "variables" => {"values" => {"foo" => "bar"}}}
end

def project_request(code)
  {"certname" => "localhost",
   "facts" => {"values" => {"my_fact" => "fact_value"}},
   "trusted_facts" => {"values" => {"my_trusted" => "trusted_value"}},
   "options" => {"capture_logs" => false, "compile_for_plan" => true},
   "versioned_project" => "fakeproject_GITSHA",
   "project_root" => "/etc/puppetlabs/puppetserver/projects",
   "modulepath" => ['/etc/puppetlabs/puppetserver/projects/fakeproject_GITSHA', '/etc/puppetlabs/puppetserver/projects/fakeproject_GITSHA/.modules'],
   "hiera_config" => ['/etc/puppetlabs/puppetserver/projects/fakeproject_GITSHA/hiera.yaml'],
   "project_name" => 'fakeproject',
   "code_ast" => generate_ast(code).to_json,
   "variables" => {"values" => {"foo" => "bar"}}}
end

def find_notify(catalog, title: nil)
  catalog['resources'].find do |item|
    next item['type'] == 'Notify' unless title
    item['type'] == 'Notify' && item['title'] == title
  end
end

describe Puppet::Server::ASTCompiler do
  context 'when compiling AST' do
    let(:boltlib_path) { nil }

    it 'handles basic resources' do
      response = Puppet::Server::ASTCompiler.compile(environment_request("notify { 'my_notify': }"), boltlib_path)
      notify = find_notify(response[:catalog])
      expect(notify).not_to be_nil
      expect(notify['title']).to eq("my_notify")
    end

    it 'correctly interpolates supplied variables' do
      response = Puppet::Server::ASTCompiler.compile(environment_request('notify { "$foo": }'), boltlib_path)
      notify = find_notify(response[:catalog])
      expect(notify).not_to be_nil
      expect(notify['title']).to eq("bar")
    end

    it 'correctly interpolates supplied facts' do
      response = Puppet::Server::ASTCompiler.compile(environment_request('notify { "$my_fact": }'), boltlib_path)
      notify = find_notify(response[:catalog])
      expect(notify).not_to be_nil
      expect(notify['title']).to eq("fact_value")
    end

    it 'correctly interpolates supplied trusted facts' do
      response = Puppet::Server::ASTCompiler.compile(environment_request('notify { "${trusted[\'my_trusted\']}": }'), boltlib_path)
      notify = find_notify(response[:catalog])
      expect(notify).not_to be_nil
      expect(notify['title']).to eq("trusted_value")
    end

    it 'fails gracefully when boltlib is not found when request requires bolt types' do
      bolt_request = environment_request('notify { "${trusted[\'my_trusted\']}": }')
      bolt_request.merge!({'options' => { 'compile_for_plan' => true } })
      expect {
        Puppet::Server::ASTCompiler.compile(bolt_request, boltlib_path)
      }.to raise_error(Puppet::Error, /the path to boltlib modules must be provided/)
    end

    context 'when compiling for an environment plan with fact and plan/target variable collisions' do
      let(:boltlib_path) { [] }

      it 'properly shadows the relevant variables' do
        # TODO: This is a temporary stub of the Bolt requires to get this
        # test to pass. We should figure out how to properly load Bolt code
        # later for the tests.
        allow(Puppet::Server::ASTCompiler).to receive(:load_bolt)
        mock_apply_inventory_class = double('Bolt::ApplyInventory')
        allow(mock_apply_inventory_class).to receive(:new).with(anything).and_return(double('mock'))
        stub_const("Bolt::ApplyInventory", mock_apply_inventory_class)

        facts = {
          "values" => {
            "plan_var_fact"   => "fact_value",
            "target_var_fact" => "fact_value",
          }
        }
        variables = {
          "values" => [
            # should be shadowed by plan_var_fact fact
            {"plan_var_fact"  => "plan_value"},
            {"plan_var_plain" => "plan_value"},
          ]
        }
        target_variables = {
          "values" => {
            # should be shadowed by target_var_fact fact
            "target_var_fact" => "target_value",
            # should be shadowed by plan_var_plain plan variable
            "plan_var_plain"  => "target_value"
          }
        }

        code = <<CODE
notify { "plan_var_fact": message => $plan_var_fact }
notify { "target_var_fact": message => $target_var_fact }
notify { "plan_var_plain":  message => $plan_var_plain }
CODE
        bolt_request = environment_request(code)
        bolt_request.merge!(
          'facts'            => facts,
          'variables'        => variables,
          'target_variables' => target_variables,
          'options'          => { 'compile_for_plan' => true },
        )

        response = Puppet::Server::ASTCompiler.compile(bolt_request, boltlib_path)
        catalog = response[:catalog]

        # assert the shadowed values
        expected_collisions = {
          'plan_var_fact'   => 'fact_value',
          'target_var_fact' => 'fact_value',
          'plan_var_plain'  => 'plan_value',
        }
        expected_collisions.each do |var, expected_value|
          notify = find_notify(catalog, title: var)
          expect(notify).not_to be_nil
          expect(notify['parameters']['message']).to eql(expected_value)
        end
      end
    end

    context 'when running a project plan' do
      let(:boltlib_path) { [] }
      it 'returns puppet settings back to normal' do
        # TODO: This is a temporary stub of the Bolt requires to get this
        # test to pass. We should figure out how to properly load Bolt code
        # later for the tests.
        allow(Puppet::Server::ASTCompiler).to receive(:load_bolt)
        mock_apply_inventory_class = double('Bolt::ApplyInventory')
        allow(mock_apply_inventory_class).to receive(:new).with(anything).and_return(double('mock'))
        stub_const("Bolt::ApplyInventory", mock_apply_inventory_class)

        # Use a simple resource, since we aren't testing the compilation itself
        # but rather ensuring the compilation does not interfere with global state
        response = Puppet::Server::ASTCompiler.compile(project_request("notify { 'my_notify': }"), boltlib_path)
        notify = find_notify(response[:catalog])
        expect(notify).not_to be_nil
        expect(notify['title']).to eq("my_notify")
      end
    end
  end
end
