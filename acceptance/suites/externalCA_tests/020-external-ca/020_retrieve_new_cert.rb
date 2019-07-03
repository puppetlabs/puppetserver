test_name 'QA-1393 - C62572 - Register Agent with Puppet Master using an External CA'

lockfile = '/opt/puppetlabs/puppet/cache/state/agent_catalog_run.lock'

step 'Run puppet agent --test on all agents to retrieve new certificates' do
  agents.each do |my_agent|
    # Wait for (possible) current agent run to complete
    result = shell("test -e #{lockfile}", {:accept_all_exit_codes => true})
    while result.exit_code == 0 do
      puts "Sleeping 5 seconds for existing agent run"
      sleep 5
      result = shell("test -e #{lockfile}", {:accept_all_exit_codes => true})
    end
    on(my_agent, puppet('agent', '--test'), :acceptable_exit_codes => [0,2])
  end
end
