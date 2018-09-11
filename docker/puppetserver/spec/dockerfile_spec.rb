require 'puppet_docker_tools/spec_helper'

CURRENT_DIRECTORY = File.dirname(File.dirname(__FILE__))

describe 'Dockerfile' do
  include_context 'with a docker image'
  include_context 'with a docker container'

  describe 'uses the correct version of Ubuntu' do
    it_should_behave_like 'a running container', 'cat /etc/lsb-release', nil, 'Ubuntu 16.04'
  end

  describe 'has puppetserver installed' do
    it_should_behave_like 'a running container', 'dpkg -l puppetserver', 0
  end

  describe 'has the puppet user' do
    it_should_behave_like 'a running container', 'id puppet', 0
  end

  describe 'has /opt/puppetlabs/bin/puppetserver' do
    it_should_behave_like 'a running container', 'stat -L /opt/puppetlabs/bin/puppetserver', 0, 'Access: \(0755\/\-rwxr\-xr\-x\)'
  end

  describe 'Dockerfile#config' do
    it 'should expose the puppetserver port' do
      expect("#{@image_json.first['ContainerConfig']['ExposedPorts']}").to include('8140/tcp')
    end
  end

  describe 'Dockerfile#running' do
    describe 'dumb-init' do
      it_should_behave_like 'a service in a container', 'dumb-init', 'root', 'foreground', '1'
    end

    describe 'java' do
      it_should_behave_like 'a service in a container', 'java', 'puppet'
    end

    describe 'puppetserver --help' do
      it_should_behave_like 'a running container', 'puppetserver --help', 0
    end

    describe 'r10k --help' do
      it_should_behave_like 'a running container', 'r10k --help', 0
    end
  end
end
