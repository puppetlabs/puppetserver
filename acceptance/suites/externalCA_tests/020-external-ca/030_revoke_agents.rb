test_name 'QA-1393 - C62573 - Revoke Agent Certificate on a Puppet Master using an External CA'
skip_test('This test is destructive.  It works, but it is disabled until it can be improved to recertify the agents')
step 'Run puppet agent -t on all agents to retreive new certificates' do
  agents.each do |my_agent|
    on(my_agent, puppet('agent','--test'), :acceptable_exit_codes => [0,2])
  end
end

step 'Revoke certificate of Non-Master Agents' do
  agents.each do |my_agent|
    agent_fqdn = fact_on(my_agent, "fqdn").chomp
    pm_fqdn = fact_on(master, "fqdn").chomp
    if agent_fqdn != pm_fqdn
      on(ca, "cd /root/rakeca/intermediate;rake revoke CN=#{agent_fqdn}.cert")
    end
  end
end

step 'Push CRL to master & HUP puppetserver' do
  pm_fqdn = fact_on(master, "fqdn").chomp
  on(ca, "cd /root/rakeca/intermediate;scp -o stricthostkeychecking=no ca_crl.pem root@#{pm_fqdn}:/etc/puppetlabs/puppet/ssl/ca/ca_crl.pem")
  on(master, puppet('agent','--test'), :acceptable_exit_codes => [0,2])
  reload_server
  on(master, puppet('agent','--test'), :acceptable_exit_codes => [0,2])
end

step 'Verify Cert revoked on Non-Master Agents' do
  agents.each do |my_agent|
    agent_fqdn = fact_on(my_agent, "fqdn").chomp
    pm_fqdn = fact_on(master, "fqdn").chomp
    if agent_fqdn != pm_fqdn
      on(my_agent, puppet('agent','--test'), :acceptable_exit_codes => [1])
    end
  end
end


