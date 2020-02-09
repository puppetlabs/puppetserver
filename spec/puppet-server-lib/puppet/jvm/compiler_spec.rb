require 'spec_helper'

require 'puppet/server/compiler'

describe Puppet::Server::Compiler do
  let(:compiler) { Puppet::Server::Compiler.new }

  context 'when creating a node' do
    let(:certname) { 'mynode.best.website' }
    let(:environment) { 'production' }
    let(:persistence) { { 'facts' => false, 'catalog' => false } }
    let(:facts) { { 'values' => { 'hello' => 'hi' } } }
    let(:trusted_facts) { { 'values' => { 'secret' => 'm3ss4g3' } } }
    let(:transaction_uuid) { '3542fd19-86df-424a-a2b1-31c6600a4ad9' }
    let(:job_id) { '1234' }
    let(:options) { {} }

    let(:request_data) do
      {
        'certname' => certname,
        'environment' => environment,
        'persistence' => persistence,
        'facts' => facts,
        'trusted_facts' => trusted_facts,
        'transaction_uuid' => transaction_uuid,
        'job_id' => job_id,
        'options' => options
      }
    end

    let(:node) { compiler.create_node(request_data) }

    let(:pe_version_file) { '/opt/puppetlabs/server/pe_version' }

    before(:each) do
      Puppet::Node.indirection.terminus_class = :plain
    end

    it 'the node has facts set' do
      expect(node.facts.values).to eq(facts['values'])
    end

    it 'the node has trusted data set' do
      expect(node.trusted_data).to eq(trusted_facts['values'])
    end

    it 'the node has server facts set' do
      expect(node.parameters).to include('serverversion' => Puppet.version.to_s)
      expect(node.server_facts).to include('serverversion' => Puppet.version.to_s)
    end

    it 'the node has no pe_serverversion fact set when FOSS' do
      expect(node.parameters).not_to include('pe_serverversion')
      expect(node.server_facts).not_to include('pe_serverversion')
    end

    it 'the node has pe_serverversion fact set when PE' do
      allow(File).to receive(:readable?).with(pe_version_file).and_return(true)
      allow(File).to receive(:zero?).with(pe_version_file).and_return(false)
      allow(File).to receive(:read).with(pe_version_file).and_return('2019.3.0')
      expect(node.parameters).to include('pe_serverversion' => '2019.3.0')
      expect(node.server_facts).to include('pe_serverversion' => '2019.3.0')
    end

    context 'the classified node has a different environment' do
      let(:environment) { 'fancy' }

      before(:each) do
        FileUtils.mkdir_p(File.join(Puppet[:environmentpath], environment))
      end

      it 'by default uses the classified environment' do
        expect(Puppet::Node.indirection).to receive(:find).and_return(
          Puppet::Node.new(certname, environment: 'production')
        )

        expect(node.environment.name).to eq(:production)
      end

      context 'and facts are not submitted' do
        let(:facts) { nil }

        it 'requests facts from pdb after classifying and attempts to classify again' do
          allow(Puppet::Node::Facts.indirection.terminus).to receive(:name).and_return(:puppetdb)
          expect(compiler).to receive(:get_facts_from_pdb)
                  .with(certname, 'fancy')
                  .ordered
                  .and_return(Puppet::Node::Facts.new(certname))
          expect(compiler).to receive(:get_facts_from_pdb)
                  .with(certname, 'production')
                  .ordered
                  .and_return(Puppet::Node::Facts.new(certname))
          expect(Puppet::Node.indirection).to receive(:find).and_return(
            Puppet::Node.new(certname, environment: 'production')
          ).twice

          expect(node.environment.name).to eq(:production)
        end

        it 'makes a limited number of attempts to retrieve a node' do
          %w[foo bar baz qux].each do |env|
            FileUtils.mkdir_p(File.join(Puppet[:environmentpath], env))
          end

          allow(Puppet::Node::Facts.indirection.terminus).to receive(:name).and_return(:puppetdb)
          allow(compiler).to receive(:get_facts_from_pdb).and_return(Puppet::Node::Facts.new(certname))

          expect(Puppet::Node.indirection).to receive(:find).and_return(
            Puppet::Node.new(certname, environment: 'production')
          ).ordered
          expect(Puppet::Node.indirection).to receive(:find).and_return(
            Puppet::Node.new(certname, environment: 'foo')
          ).ordered
          expect(Puppet::Node.indirection).to receive(:find).and_return(
            Puppet::Node.new(certname, environment: 'bar')
          ).ordered
          expect(Puppet::Node.indirection).to receive(:find).and_return(
            Puppet::Node.new(certname, environment: 'baz')
          ).ordered
          expect(Puppet::Node.indirection).to receive(:find).and_return(
            Puppet::Node.new(certname, environment: 'qux')
          ).ordered

          expect { compiler.create_node(request_data) }
            .to raise_error(Puppet::Error, /environment didn't stabilize/)
        end
      end

      context 'and prefer_requested_environment is set' do
        let(:options) { { 'prefer_requested_environment' => true } }

        it 'uses the environment in the request' do
          expect(Puppet::Node.indirection).to receive(:find).and_return(
            Puppet::Node.new(certname, environment: 'production')
          )

          expect(node.environment.name).to eq(:fancy)
        end
      end
    end
  end
end
