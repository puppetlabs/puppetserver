#! /usr/bin/env ruby

require 'rspec/core'

describe 'puppetserver container' do
  def puppetserver_health_check(container)
    status = %x(docker inspect "#{container}" --format '{{.State.Health.Status}}').chomp
    STDOUT.puts "queried health status of #{container}: #{status}"
    return status
  end

  before(:all) do
    %x(docker pull puppet/puppet-agent-alpine:latest)
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

    @container = %x(docker run --rm --detach --env DNS_ALT_NAMES=puppet --name puppet.test --hostname puppet.test #{@image}).chomp
  end

  after(:all) do
    %x(docker container kill #{@container}) unless @container.nil?
  end

  it 'should start puppetserver successfully' do
    status = puppetserver_health_check(@container)
    while (status == 'starting' || status == "'starting'")
      sleep(1)
      status = puppetserver_health_check(@container)
    end
    if status !~ /\'?healthy\'?/
      puts %x(docker logs #{@container})
    end
    expect(status).to match(/\'?healthy\'?/)
  end

  it 'should be able to run a puppet agent against the puppetserver' do
    output = %x(docker run --rm --name puppet-agent.test --hostname puppet-agent.test --link puppet.test puppet/puppet-agent-alpine:latest agent --test --server puppet.test)
    status = $?.exitstatus
    puts output
    expect(status).to eq(0)
  end

  it 'should have r10k available' do
    %x(docker exec puppet.test r10k --help)
    status = $?.exitstatus
    expect(status).to eq(0)
  end
end
