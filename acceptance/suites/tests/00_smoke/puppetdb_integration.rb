## Tests that PuppetDB can be integrated with Puppet Server in a simple
## monolithic install (i.e. PuppetDB on same node as Puppet Server).
##
## In this context 'integrated' just means that Puppet Server is able to
## communicate over HTTP/S with PuppetDB to send it information, such as
## agent run reports.
##
## This test validates communication is successful by querying the PuppetDB HTTP
## API and asserting that an updated factset, catalog and report from an agent
## run made it into PuppetDB.
#

# We only run this test if we'll have puppetdb installed, which is gated in
# acceptance/suites/pre_suite/foss/95_install_pdb.rb using the same conditional
matching_puppetdb_platform = puppetdb_supported_platforms.select { |r| r =~ master.platform }
skip_test unless matching_puppetdb_platform.length > 0

require 'json'
require 'time'

run_timestamp = nil

with_puppet_running_on(master, {}) do
  step 'Enable PuppetDB' do
    apply_manifest_on(master, <<EOM)
class{'puppetdb::master::config':
  enable_reports          => true,
  manage_report_processor => true,
}
EOM
  end

  step 'Run agent to trigger data submission to PuppetDB' do
    run_timestamp = Time.now.utc
    on(master, puppet_agent("--test --server #{master}"), :acceptable_exit_codes => [0,2])
  end
end

step 'Validate server sent agent data to PuppetDB' do
  fqdn = on(master, '/opt/puppetlabs/bin/facter fqdn').stdout.chomp
  query = "curl http://localhost:8080/pdb/query/v4/nodes/#{fqdn}"
  response = JSON.parse(on(master, query).stdout.chomp)
  %w[facts_timestamp catalog_timestamp report_timestamp].each do |dataset|
    assert_operator(Time.iso8601(response[dataset]),
                    :>,
                    run_timestamp,
                   "#{dataset} updated in PuppetDB")
  end
end
