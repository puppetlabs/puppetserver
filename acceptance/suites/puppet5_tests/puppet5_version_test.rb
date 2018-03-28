test_name "Testing Master/Agent backwards compatibility"

# Agent running on the master is current, not legacy.
legacy_agents = agents.reject { |agent| agent == master }

step "Check that legacy agents have Puppet 5.x installed"
on(legacy_agents, puppet("--version")) do
  assert_match(/\A5\./, stdout, "puppet --version does not start with major version 5.")
end

step "Check that Puppet Server has Puppet 6.x installed"
on(master, puppet("--version")) do
  assert_match(/\A6/, stdout, "puppet --version does not start with major version 6.x")
end

step "Check that the agent on the master runs against the master"
with_puppet_running_on(master, {}) do
  agent_cmd = puppet("agent --test --server #{master}")
  on(master, agent_cmd, :acceptable_exit_codes => [0,2])
end
