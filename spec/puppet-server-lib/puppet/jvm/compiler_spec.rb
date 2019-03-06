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
    let(:options) { {} }

    let(:request_data) do
      {
        'certname' => certname,
        'environment' => environment,
        'persistence' => persistence,
        'facts' => facts,
        'trusted_facts' => trusted_facts,
        'transaction_uuid' => transaction_uuid,
        'options' => options
      }
    end

    let(:node) { compiler.create_node(request_data) }

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

    context 'the classified node has a different environment' do
      let(:environment) { 'fancy' }

      before(:each) do
        FileUtils.mkdir_p(File.join(Puppet[:environmentpath], environment))

        Puppet::Node.indirection.expects(:find).returns(
          Puppet::Node.new(certname, environment: 'production')
        )
      end

      it 'by default uses the classified environment' do
        expect(node.environment.name).to eq(:production)
      end

      context 'and prefer_requested_environment is set' do
        let(:options) { { 'prefer_requested_environment' => true } }

        it 'uses the environment in the request' do
          expect(node.environment.name).to eq(:fancy)
        end
      end
    end
  end
end
