# so much code lifted from
# https://github.com/puppetlabs/pe_acceptance_tests/blob/2015.2.x/setup/scale/install/20_classification/10_compile_masters.rb

require 'scooter'
#require 'pry'
#require 'pry-debugger'


test_name 'Classify Compile Master Nodes' do
  skip_test 'No compile masters' unless any_hosts_as?(:compile_master)
  api = Scooter::HttpDispatchers::ConsoleDispatcher.new(dashboard)

  step 'enable ca_proxy' do
    # since this is File Sync Test setup, we are assuming this is at least PE 2015.2
    pe_master_group = api.get_node_group_by_name('PE Master')
    pe_master_group['classes']['puppet_enterprise::profile::master']['enable_ca_proxy'] = true
    api.replace_node_group(pe_master_group['id'], pe_master_group)
  end 

  # do we need to put our PE nodes in the MCollective node group?

  step 'pin compile masters to PE Masters node group' do
    pe_master_group = api.get_node_group_by_name('PE Master')
    pe_master_group['rule'] += [compile_master].flatten.map do |server|
      [ '=', 'name', server.node_name ]
    end
    
    api.replace_node_group(pe_master_group['id'], pe_master_group)
  end
end

on compile_master, puppet('resource', 'user', 'pe-puppet', 'ensure=present')

test_name 'Install Compile Masters' do
  step 'export new masters certificate whitelist resources' do
    # Temp make this accept a 6, as on ec2 it will fail because of pe_repo
    on compile_master, puppet_agent('-t'), :acceptable_exit_codes => [0,2,6]
  end
  
  step 'update certificate whitelist on puppetdb' do
    on database, puppet_agent('-t'), :acceptable_exit_codes => [0,2]
    # The puppet run earlier will have scheduled a puppetdb restart so the cert
    # whitelist can take effect.  Wait for it to come back up
    sleep_until_puppetdb_started(database)
  end
      
  step 'update rbac, dashboard certificate whitelist on console' do
    on dashboard, puppet_agent('-t'), :acceptable_exit_codes => [0,2]
    # The puppet run earlier will have scheduled a console-services restart so the cert
    # whitelist can take effect.  Wait for it to come back up
    sleep_until_nc_started(dashboard)
  end

end

