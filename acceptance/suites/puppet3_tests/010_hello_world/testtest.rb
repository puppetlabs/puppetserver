test_name "Testing Master/Agent backwards compatibility"

# Agent running on the master is current, not legacy.
legacy_agents = agents.reject { |agent| agent == master }

step "Check that legacy agents have Puppet 3.x installed"
on(legacy_agents, puppet("--version")) do
  assert(stdout.start_with? "3.", "puppet --version does not start with major version 3.")
end

step "Check that Puppet Server has Puppet 4.x installed"
on(master, puppet("--version")) do
  assert(stdout.start_with? "4.", "puppet --version does not start with major version 4.")
end
