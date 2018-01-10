## Tests that PuppetDB can be integrated with Puppet Server in a simple
## monolithic install (i.e. PuppetDB on same node as Puppet Server).
##
## In this context 'integrated' just means that Puppet Server is able to
## communicate over HTTP/S with PuppetDB to send it information, such as
## agent run reports.
##
## This test validates communication is successful by querying the PuppetDB HTTP
## API and asserting the agent's report timestamp is not null. This means that
## PuppetDB successfully received the agent's report sent from Puppet Server.
## We can just run the agent that's on the master for this.
#

# We only run this test if we'll have puppetdb installed, which is gated in
# acceptance/suites/pre_suite/foss/95_install_pdb.rb using the same conditional
matching_puppetdb_platform = puppetdb_supported_platforms.select { |r| r =~ master.platform }
skip_test unless matching_puppetdb_platform.length > 0

require 'json'

step 'Submit agent report to PuppetDB via server' do
  with_puppet_running_on(master, {}) do
    on(master, puppet_agent("--test --server #{master}"), :acceptable_exit_codes => [0,2])
  end
end

step 'Validate server sent agent report to PuppetDB' do
  fqdn = on(master, '/opt/puppetlabs/bin/facter fqdn').stdout.chomp
  query = "curl http://localhost:8080/pdb/query/v4/nodes/#{fqdn}"
  response = JSON.parse(on(master, query).stdout.chomp)
  assert(response['report_timestamp'] != nil)
end
