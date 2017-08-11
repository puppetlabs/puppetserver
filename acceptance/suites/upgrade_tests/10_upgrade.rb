require 'puppetserver/acceptance/compat_utils'
test_name "Upgrade Puppetserver"

install_opts = options.merge({ :dev_builds_repos => ["PC1"]})

package_build_version = ENV['PACKAGE_BUILD_VERSION']
if package_build_version.nil?
  abort("Environment variable PACKAGE_BUILD_VERSION required for package installs!")
end

step "Setup Puppet Server dev repository on the master." do
  install_puppetlabs_dev_repo(master, 'puppetserver', package_build_version, nil, install_opts)
end

step "Setup Puppet dev repository on all nodes." do
  hosts.each do |host|
    install_puppetlabs_dev_repo(host, 'puppet-agent', test_config[:puppet_build_version], nil, install_opts)
  end
end

step "Upgrade Puppet Server." do
  install_puppet_server_deps
  master.upgrade_package('puppetserver')
  maybe_configure_jruby9k
  on(master, puppet("resource service puppetserver ensure=stopped"))
  on(master, puppet("resource service puppetserver ensure=running"))
end

logfile = "/var/log/puppetlabs/puppetserver/puppetserver.log"
timestamp = ''

step "Get timestamp from puppetserver log" do
  res = on(master, "tail -1 #{logfile}")
  m = res.stdout.match(/^(.*),/)
  timestamp = if m
                Time.parse(m[1])
              else
                Time.parse('9999-09-09')
              end
end

step "Upgrade Puppet agents" do
  nonmaster_agents.each do |agent|
    agent.upgrade_package('puppet-agent')
  end
end

step "Check that the master has Puppetserver 5.x installed" do
  on(master, "puppetserver --version") do
    assert_match(/\Apuppetserver version: 5\./i, stdout, "puppetserver --version does not start with major version 5.")
  end
end

step "Check that the puppetserver has been restarted since the agent upgrade" do
  res = on(master, "tac #{logfile} | grep -m1 \"Server] Started\"")
  m = res.stdout.match(/^(.*),/)
  newtime = if m
              Time.parse(m[1])
            else
              Time.parse('0000-01-01')
            end
  fail_test('puppetserver service was not restarted after agent upgrade') unless newtime > timestamp
end

step "Verify that agents can connect to the server" do
  on hosts, puppet("agent --test --server #{master}"), :acceptable_exit_codes => [0]
end
