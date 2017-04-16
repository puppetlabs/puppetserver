test_name "(SERVER-571) Test hiera backend plugins located in modules"

# The way this "works" is that the user installs a module on the server, the
# agent run synchronizes the module lib/ plugins to the [Puppet Installation
# Layout][layout] `libdir` path of /opt/puppetlabs/puppet/cache/lib  The
# master then loads the synchronized plugin file using a ruby `require` which
# searches the $LOAD_PATH
#
# [layout]: http://goo.gl/BWyXpo

this_file = __FILE__
# The local directory where resources are located, e.g. an environment directory
rsrc_dir = File.expand_path('../../../../resources', this_file)

# The target directory to place whole environments.
# e.g. /etc/puppetlabs/code/environments
env_path = on(master, puppet("config print environmentpath")).stdout.chomp
target_dir = env_path.split(master['pathseparator']).first

scp_options = {}
step "Install hiera_test module in server571 environment on the master"
Dir.chdir("#{rsrc_dir}/environments") do
  # Create /etc/puppetlabs/code/environments/server571
  # so that agents can run against this environment to sync plugins
  master.do_scp_to "server571", target_dir, scp_options
end

# Save the hiera configuration
step "Save hiera_config file to restore in teardown"
hiera_config = on(master, puppet('config print hiera_config')).stdout.chomp
on(master, "cp -p #{hiera_config} #{hiera_config}.bak")

step "Write hiera configuration that uses the backend from the module"
Dir.chdir("#{rsrc_dir}/files/server571") do
  master.do_scp_to "hiera.yaml", hiera_config, scp_options
end

teardown do
  on(master, "mv -f #{hiera_config}.bak #{hiera_config}")
end

with_puppet_running_on(master, {}) do
  step "Run puppet agent on the master to pluginsync the hiera backend"
  agent_run_cmd = "agent --test --server #{master} --environment=server571"
  on(master, puppet(agent_run_cmd), :acceptable_exit_codes => [0,2])

  # Assert the hiera backend returns a value
  step "Verify data from the hiera backend is in the compiled catalog"
  on(master, puppet(agent_run_cmd), :acceptable_exit_codes => [0,2]) do |result|
    assert_match /4EB0033A-FC4E-4D7D-9453-5A9938B6FF61/, result.stdout,
      "The agent output should contain the value from the Hiera backend"
  end
end
