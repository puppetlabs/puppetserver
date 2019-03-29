#! /usr/bin/env ruby

require 'rspec/core'
require 'open3'

describe 'puppetserver container' do
  include Helpers

  def puppetserver_health_check(container)
    result = run_command("docker inspect \"#{container}\" --format '{{.State.Health.Status}}'")
    status = result[:stdout].chomp
    STDOUT.puts "queried health status of #{container}: #{status}"
    return status
  end

  before(:all) do
    run_command('docker pull puppet/puppet-agent-alpine:latest')
    @image = ENV['PUPPET_TEST_DOCKER_IMAGE']
    if @image.nil?
      error_message = <<-MSG
* * * * *
  PUPPET_TEST_DOCKER_IMAGE environment variable must be set so we
  know which image to test against!
* * * * *
      MSG
      fail error_message
    end

    # Windows doesn't have the default 'bridge' network driver
    network_opt = File::ALT_SEPARATOR.nil? ? '' : '--driver=nat'

    result = run_command("docker network create #{network_opt} puppetserver_test_network")
    fail 'Failed to create network' unless result[:status].exitstatus == 0
    @network = result[:stdout].chomp

    result = run_command("docker run --rm --detach \
                   --env DNS_ALT_NAMES=puppet \
                   --env PUPPERWARE_DISABLE_ANALYTICS=true \
                   --name puppet.test \
                   --network #{@network} \
                   --hostname puppet.test \
                   #{@image}")
    fail 'Failed to create puppet.test' unless result[:status].exitstatus == 0
    @container = result[:stdout].chomp

    result = run_command("docker run --rm --detach \
                   --env DNS_ALT_NAMES=puppet \
                   --env PUPPERWARE_DISABLE_ANALYTICS=true \
                   --env CA_ENABLED=false \
                   --env CA_HOSTNAME=puppet.test \
                   --network #{@network} \
                   --name puppet-compiler.test \
                   --hostname puppet-compiler.test \
                   #{@image}")
    fail 'Failed to create compiler' unless result[:status].exitstatus == 0
    @compiler = result[:stdout].chomp
  end

  after(:all) do
    run_command("docker container kill #{@container}") unless @container.nil?
    run_command("docker container kill #{@compiler}") unless @compiler.nil?
    run_command("docker network rm #{@network}") unless @network.nil?
  end

  it 'should start puppetserver successfully' do
    status = puppetserver_health_check(@container)
    while (status == 'starting' || status == "'starting'" || status.empty?)
      sleep(1)
      status = puppetserver_health_check(@container)
    end
    if status !~ /\'?healthy\'?/
      run_command("docker logs #{@container}")
    end
    expect(status).to match(/\'?healthy\'?/)
  end

  it 'should be able to run a puppet agent against the puppetserver' do
    result = run_command("docker run --rm --name puppet-agent.test --hostname puppet-agent.test --network #{@network} puppet/puppet-agent-alpine:latest agent --test --server puppet.test")
    expect(result[:status].exitstatus).to eq(0)
  end

  it 'should be able to start a compile master' do
    status = puppetserver_health_check(@compiler)
    while (status == 'starting' || status == "'starting'" || status.empty?)
      sleep(1)
      status = puppetserver_health_check(@compiler)
    end
    if status !~ /\'?healthy\'?/
      run_command("docker logs #{@compiler}")
    end
    expect(status).to match(/\'?healthy\'?/)
end

  it 'should be able to run an agent against the compile master' do
    result = run_command("docker run --rm --name puppet-agent-compiler.test --hostname puppet-agent-compiler.test --network #{@network} puppet/puppet-agent-alpine:latest agent --test --server puppet-compiler.test --ca_server puppet.test")
    expect(result[:status].exitstatus).to eq(0)
  end

  it 'should have r10k available' do
    result = run_command('docker exec puppet.test r10k --help')
    expect(result[:status].exitstatus).to eq(0)
  end
end
