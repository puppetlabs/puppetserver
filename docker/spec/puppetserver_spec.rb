require 'rspec/core'
require 'pupperware/spec_helper'
include Pupperware::SpecHelpers

RSpec.configure do |c|
  c.before(:suite) do
    require_test_image()
    teardown_cluster()
    run_command('docker pull puppet/puppet-agent-ubuntu:latest')
    docker_compose_up()
  end

  c.after(:suite) do
    emit_logs
    teardown_cluster()
  end
end

describe 'puppetserver container' do
  it 'should be able to run a puppet agent against the puppetserver' do
    expect(run_agent('puppet-agent', 'puppetserver_test', masterport: '8141')).to eq(0)
  end

  it 'should be able to run an agent against the compile master' do
    expect(run_agent('compiler-agent', 'puppetserver_test', server: get_container_hostname(get_service_container('compiler')), ca: get_container_hostname(get_service_container('puppet')), ca_port: '8141')).to eq(0)
  end

  it 'should have r10k available' do
    result = docker_compose('exec -T puppet r10k --help')
    expect(result[:status].exitstatus).to eq(0)
  end
end
