#! /usr/bin/env ruby

require 'rspec/core'
require 'open3'
require 'pupperware/spec_helper'

describe 'puppetserver container' do
  include Pupperware::SpecHelpers

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

    run_command('docker-compose --no-ansi up --detach')
  end

  after(:all) do
    emit_logs()
    teardown_cluster()
  end

  it 'should start puppetserver successfully' do
    expect(wait_on_puppetserver_status()).to eq ('healthy')
  end

  it 'should be able to run a puppet agent against the puppetserver' do
    expect(run_agent('puppet-agent.test', 'puppetserver_test')).to eq(0)
  end

  it 'should be able to start a compile master' do
    expect(wait_on_puppetserver_status(180, 'compiler')).to eq ('healthy')
  end

  it 'should be able to run an agent against the compile master' do
    expect(run_agent('compiler-agent.test', 'puppetserver_test', get_container_hostname(get_service_container('compiler')), get_container_hostname(get_service_container('puppet')))).to eq(0)
  end

  it 'should have r10k available' do
    result = run_command('docker-compose exec -T puppet r10k --help')
    expect(result[:status].exitstatus).to eq(0)
  end
end
