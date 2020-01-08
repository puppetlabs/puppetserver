require 'net/http'
require 'json'
require 'rspec/expectations'

iterations = 100
processes_per_catalog = 2
include RSpec::Matchers

class CatalogTester
  def initialize
    @uri = URI("http://localhost:8140/puppet/v3/catalog/#{@catalog}?environment=#{@environment}")
  end

  def run
    encoded_result = Net::HTTP.get(@uri)
    result = JSON.parse(encoded_result)
    verify(result)
  end

  def verify(_result)
    raise NotImplementedError
  end
end

class CatalogOneTester < CatalogTester
  def initialize
    @catalog = 'testone'
    @environment = 'production'
    super
  end

  def verify(result)
    expect(result).to include('name' => @catalog)
    expect(result).to include('environment' => @environment)
    # v4 function
    expect(result['resources']).to include(a_hash_including('title' => 'do it'))
    # v3 function
    expect(result['resources']).to include(a_hash_including('title' => 'the old way of functions'))
    # class param from hiera
    expect(result['resources']).to include(a_hash_including('parameters' => { 'input' => 'froyo' }))
  end
end

class CatalogTwoTester < CatalogTester
  def initialize
    @catalog = 'testtwo'
    @environment = 'funky'
    super
  end

  def verify(result)
    expect(result).to include('name' => @catalog)
    expect(result).to include('environment' => @environment)
    # v4 function
    expect(result['resources']).to include(a_hash_including('title' => 'Always on the one'))
    # v3 function
    expect(result['resources']).to include(a_hash_including('title' => 'old school v3 function'))
    # class param from hiera
    expect(result['resources']).to include(a_hash_including('parameters' => { 'input' => 'hiera_funky' }))
  end
end

processes_per_catalog.times do
  Process.fork do
    tester = CatalogOneTester.new
    iterations.times do
      tester.run
    end
  end
end

processes_per_catalog.times do
  Process.fork do
    tester = CatalogTwoTester.new
    iterations.times do
      tester.run
    end
  end
end

exit_codes = Process.waitall

if exit_codes.all? { |s| s[1].exitstatus.zero? }
  exit 0
else
  exit 1
end
