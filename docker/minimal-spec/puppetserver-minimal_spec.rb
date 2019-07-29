#! /usr/bin/env ruby

require 'rspec/core'
require 'open3'
require 'pupperware/spec_helper'

describe 'puppetserver container' do
  include Pupperware::SpecHelpers

  def run_agent2(agent_name, network, server: get_container_hostname(get_service_container('puppet')), ca: get_container_hostname(get_service_container('puppet')), masterport: 8140, ca_port: nil)
    # default ca_port to masterport if unset
    ca_port = masterport if ca_port.nil?

    # setting up a Windows TTY is difficult, so we don't
    # allocating a TTY will show container pull output on Linux, but that's not good for tests
    STDOUT.puts("running agent #{agent_name} in network #{network} against #{server}")
    result = run_command("docker run --rm --network #{network} --network-alias=#{agent_name} --dns=127.0.0.11 --name #{agent_name} --hostname #{agent_name} puppet/puppet-agent-ubuntu agent --verbose --onetime --no-daemonize --summarize --server #{server} --masterport #{masterport} --ca_server #{ca} --ca_port #{ca_port}")
    return result[:status].exitstatus
  end

  before(:all) do
    run_command('docker pull puppet/puppet-agent-ubuntu:latest')
    if ENV['PUPPET_TEST_DOCKER_IMAGE'].nil?
      fail <<-MSG
      error_message = <<-MSG
  * * * * *
  PUPPET_TEST_DOCKER_IMAGE environment variable must be set so we
  know which image to test against!
  * * * * *
      MSG
    end

    status = run_command('docker-compose --no-ansi version')[:status]
    if status.exitstatus != 0
      fail "`docker-compose` must be installed and available in your PATH"
    end

    teardown_cluster()

    run_command('docker-compose --no-ansi --file docker-compose-minimal.yml up --detach')
  end

  after(:all) do
    emit_logs()
    teardown_cluster()
  end

  it 'should start puppetserver successfully' do
    expect(wait_on_puppetserver_status()).to eq ('healthy')
  end

  it 'should be able to run a puppet agent against the puppetserver' do
    expect(run_agent2('puppet-agent.test', 'puppetserver_test', masterport: '8141')).to eq(0)
  end

  it 'should have r10k available' do
    result = run_command('docker-compose exec -T puppet r10k --help')
    expect(result[:status].exitstatus).to eq(0)
  end
end
