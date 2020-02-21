require 'net/http'
require 'json'
require 'rspec/expectations'
require 'fileutils'

include RSpec::Matchers

class CacheTester
  def initialize
    @nodename        = 'testone'
    @environment     = 'cache_test'
    @environment_uri = URI("http://localhost:8140/puppet/v3/catalog/#{@nodename}?environment=#{@environment}")
    @cache_uri       = URI("http://localhost:8140/puppet-admin-api/v1/environment-cache")
  end

  # Needs updating
  def modify_environment
    modules_path = File.join(__dir__, 'environments', @environment, 'modules')
    tmp_path = File.join(__dir__, 'for-now')
    FileUtils.mkdir_p(tmp_path)
    FileUtils.mv(modules_path, tmp_path)
  end

  def invalidate_cache
    http = Net::HTTP.new(@cache_uri.host, @cache_uri.port)
    result = http.delete(@cache_uri.path)
    expect(result).to be_a(Net::HTTPNoContent)
  end

  def verify(result)
    expect(result).to include('name' => @nodename)
    expect(result).to include('environment' => @environment)
    expect(result['resources']).to include(a_hash_including('title' => /hello/))
  end

  def run
    encoded_result = Net::HTTP.get(@environment_uri)
    result = JSON.parse(encoded_result)
    verify(result)
  end

  # Needs updating
  def reset_environment
    env_path = File.join(__dir__, 'environments', @environment)
    tmp_path = File.join(__dir__, 'for-now', 'modules')
    FileUtils.mv(tmp_path, env_path)
  end
end

# Do the thing
iterations = 100

tester = CacheTester.new
Process.fork {
  iterations.times { |i|
    tester.run
    puts "completed iteration #{i+1}"
  }
}
#tester.modify_environment
#tester.invalidate_cache

exit_codes = Process.waitall
#tester.reset_environment

if exit_codes.all? { |s| s[1].exitstatus.zero? }
  exit 0
else
  exit 1
end
