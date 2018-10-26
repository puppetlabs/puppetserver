#! /usr/bin/env ruby

require 'rspec/core'
require 'time'
require 'date'
require 'open3'

# lint the container
def lint(container, is_nightly: false)
  local_exec = false
  begin
    %x(hadolint --help)
    if $?.exitstatus == 0
      local_exec = true
    end
  rescue Errno::ENOENT
    puts "Did not find hadolint in your path, running from docker container"
  end

  dockerfile = get_dockerfile(is_nightly)
  command = "hadolint --ignore DL3008 --ignore DL3018 --ignore DL4000 --ignore DL4001"

  if local_exec
    command = "#{command} #{File.join(Dir.pwd, container, dockerfile)}"
  else
    lint_image = 'hadolint/hadolint:latest'
    output = %x(docker pull #{lint_image})
    status = $?.exitstatus
    puts output
    fail "Fetching #{lint_image} failed!" unless status == 0
    command = "docker run --rm -v #{File.join(Dir.pwd, container, dockerfile)}:/Dockerfile:ro -i #{lint_image} #{command} Dockerfile"
  end

  # run this command from the 'docker' directory
  Dir.chdir(File.dirname(File.dirname(__FILE__))) do
    output = %x(#{command})
    status = $?.exitstatus
    puts output
    fail "Running hadolint against #{File.join(container, dockerfile)} failed!" unless status == 0
  end
end

def get_dockerfile(is_nightly = false)
  return 'Dockerfile.nightly' if is_nightly
  return 'Dockerfile'
end

def get_version(is_nightly = false)
  if is_nightly
    return 'puppet6-nightly'
  end

  # run this command from the root level
  # ------------------------------------- ci
  # ------------------------- docker
  # ----------- puppetserver
  Dir.chdir(File.dirname(File.dirname(File.dirname(__FILE__)))) do
    git_dir = %x(git rev-parse --git-dir).chomp
    if File.file?(File.join(git_dir, 'shallow'))
      %x(git pull --unshallow)
    end
    %x(git fetch origin 'refs/tags/*:refs/tags/*')
    describe = %x(git describe).chomp
    describe.sub!(/-.*/, '')
    return describe
  end
  return ''
end

# build the containers
def build(container, repository: 'puppet', version: '', is_latest: false, is_nightly: false)
  # run this command from the 'docker' directory
  Dir.chdir(File.dirname(File.dirname(__FILE__))) do
    vcs_ref = %x(git rev-parse HEAD).strip
    build_date = Time.now.utc.iso8601

    dockerfile = get_dockerfile(is_nightly)
    version = get_version(is_nightly) unless !version.empty?
    if version.empty?
      fail "no version!"
    end
    path = "#{repository}/#{container}"
    tags = ['--tag', "#{path}:#{version}"]
    if is_latest
      tags << ['--tag', "#{path}:latest"]
      tags.flatten!
    end

    build_command = [ 'docker', 'build', '--build-arg', "vcs_ref=#{vcs_ref}", '--build-arg', "build_date=#{build_date}", '--build-arg', "version=#{version}", '--file', File.join(container, dockerfile), tags, container].flatten

    Open3.popen2e(*build_command) do |stdin, output_stream, wait_thread|
      output_stream.each_line do |line|
        puts line
      end
      exit_status = wait_thread.value.exitstatus
      fail "Building #{container} failed!" unless exit_status == 0
    end
  end
end

# spec test the container
def spec(container, repository: 'puppet', version: '', is_nightly: false)
  # run this command from the 'docker' directory
  Dir.chdir(File.dirname(File.dirname(__FILE__))) do
    version = get_version(is_nightly) unless !version.empty?
    if version.empty?
      fail "no version!"
    end

    ENV['PUPPET_TEST_DOCKER_IMAGE'] = "#{repository}/#{container}:#{version}"

    tests = Dir.glob(File.join(container, 'spec', '*_spec.rb'))
    success = true
    tests.each do |test|
      Open3.popen2e('rspec', 'spec', test) do |stdin, output_stream, wait_thread|
        output_stream.each_line do |line|
          puts line
        end
        exit_status = wait_thread.value.exitstatus
        success = success && (exit_status == 0)
      end
    end

    ENV['PUPPET_TEST_DOCKER_IMAGE'] = nil

    fail "Running RSpec tests for #{container} failed!" unless success
  end
end

# push the containers
def publish(container, repository: 'puppet', version: '', is_latest: false, is_nightly: false)
  version = get_version(is_nightly) unless !version.empty?

  versions = [version]
  versions << 'latest' if is_latest

  versions.each do |v|
    Open3.popen2e('docker', 'push', "#{repository}/#{container}:#{v}") do |stdin, output_stream, wait_thread|
      output_stream.each_line do |line|
        puts line
      end
      exit_status = wait_thread.value.exitstatus
      fail "Publishing #{repository}/#{container}:#{v} failed!" unless exit_status == 0
    end
  end
end
