require 'puppet/server/compiler'
require 'puppet/indirector/facts/mock'
require 'puppet/indirector/catalog/mock'

describe Puppet::Server::Compiler do
  let(:request) do
    {
      "certname" => "foobarr",
      "environment" => "production",
      "persistence" => {
        "facts" => true,
        "catalog" => true
      },
      "facts" => {
        "values" => {
          "foo" => "bar"
        }
      },
      "trusted_facts" => {
        "values" => {
          "foo" => "bar"
        }
      }
    }
  end


  it 'can be instantiated' do
    Puppet::Server::Compiler.new
  end

  it 'calls termini to save as needed' do
    Puppet::Indirector::Terminus.register_terminus_class(Puppet::Node::Facts::Mock)
    Puppet::Node::Facts.indirection.terminus_class = :mock
    Puppet::Node::Facts::Mock.any_instance.expects(:save)

    Puppet::Indirector::Terminus.register_terminus_class(Puppet::Resource::Catalog::Mock)
    Puppet::Resource::Catalog.indirection.terminus_class = :mock
    Puppet::Resource::Catalog::Mock.any_instance.expects(:save)

    compiler = Puppet::Server::Compiler.new
    compiler.compile(request)
  end
end
