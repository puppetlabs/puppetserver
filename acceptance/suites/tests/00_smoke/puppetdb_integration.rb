## Tests that PuppetDB can be integrated with Puppet Server in a simple
## monolithic install (i.e. PuppetDB on same node as Puppet Server).
##
## In this context 'integrated' just means that Puppet Server is able to
## communicate over HTTP/S with PuppetDB to send it information, such as
## agent run reports.
##
## This test validates communication is successful by querying the PuppetDB HTTP
## API and asserting that an updated factset, catalog and report from an agent
## run made it into PuppetDB. Additionally, the STDOUT of the agent run is
## tested for the presence of a Notify resource that was exported by another
## node.
##
## Finally, the output of the Puppet Server HTTP /status API is tested
## to ensure that metrics related to PuppetDB communication were recorded.
#

# We only run this test if we'll have puppetdb installed, which is gated in
# acceptance/suites/pre_suite/foss/95_install_pdb.rb using the same conditional
matching_puppetdb_platform = puppetdb_supported_platforms.select { |r| r =~ master.platform }
skip_test unless matching_puppetdb_platform.length > 0
skip_test if master.is_pe?

require 'json'
require 'time'
require 'securerandom'

run_timestamp = nil
master_fqdn = on(master, '/opt/puppetlabs/bin/facter fqdn').stdout.chomp
random_string = SecureRandom.urlsafe_base64.freeze

step 'Configure site.pp for PuppetDB' do
  sitepp = '/etc/puppetlabs/code/environments/production/manifests/site.pp'
  create_remote_file(master, sitepp, <<EOM)
node 'resource-exporter.test' {
  @@notify{'#{random_string}': }
}

node '#{master_fqdn}' {
  Notify<<| title == '#{random_string}' |>>

  # Dummy query to record a hit for the PuppetDB query API to metrics.
  $_ = puppetdb_query(['from', 'nodes', ['extract', 'certname']])
}
EOM
  on(master, "chmod 644 #{sitepp}")

  teardown do
    on(master, "rm -f #{sitepp}")
  end
end

with_puppet_running_on(master, {}) do
  step 'Enable PuppetDB' do
    apply_manifest_on(master, <<EOM)
class{'puppetdb::master::config':
  enable_reports          => true,
  manage_report_processor => true,
}
EOM
  end

  step 'Run agent to generate exported resources' do
    # This test compiles a catalog using a differnt certname so that
    # later runs can test collection.
    on(master, 'puppetserver ca generate --certname=resource-exporter.test')

    teardown do
      on(master, puppet('node', 'deactivate', 'resource-exporter.test'))
      on(master, 'puppetserver ca clean --certname=resource-exporter.test')
    end

    on(master, puppet_agent('--test', '--noop',
                            '--server', master_fqdn,
                            '--certname', 'resource-exporter.test'),
              :acceptable_exit_codes => [0,2])
  end

  step 'Run agent to trigger data submission to PuppetDB' do
    # ISO 8601 timestamp, with milliseconds and time zone. Local time is used
    # instead of UTC as both PuppetDB and Puppet Server log in local time.
    run_timestamp = Time.iso8601(on(master, 'date +"%Y-%m-%dT%H:%M:%S.%3N%:z"').stdout.chomp)
    on(master, puppet_agent("--test --server #{master_fqdn}"), :acceptable_exit_codes => [0,2]) do
      assert_match(/Notice: #{random_string}/, stdout,
                  'Puppet run collects exported Notify')
    end
  end

  step 'Validate PuppetDB metrics captured by puppet-profiler service' do
    query = "curl -k https://localhost:8140/status/v1/services/puppet-profiler?level=debug"
    response = JSON.parse(on(master, query).stdout.chomp)
    pdb_metrics = response['status']['experimental']['puppetdb-metrics']

    # NOTE: If these tests fail, then likely someone changed a metric
    # name passed to Puppet::Util::Profiler.profile over in the Ruby
    # terminus code of the PuppetDB project without realizing that is a
    # breaking change to metrics critical for measuring compiler performance.
    %w[
      facts_encode command_submit_replace_facts
      catalog_munge command_submit_replace_catalog
      report_convert_to_wire_format_hash command_submit_store_report
      resource_search query
    ].each do |metric_name|
      metric_data = pdb_metrics.find({}) {|m| m['metric'] == metric_name }

      assert_operator(metric_data.fetch('count', 0), :>, 0,
                      "PuppetDB metrics recorded for: #{metric_name}")
    end
  end
end

step 'Validate PuppetDB successfully stored agent data' do
  query = "curl http://localhost:8080/pdb/query/v4/nodes/#{master_fqdn}"
  agent_datasets = %w[facts_timestamp catalog_timestamp report_timestamp]
  missing_datasets = [ ]
  retries = 3

  retries.times do |i|
    logger.debug("PuppetDB query attempt #{i} for updated agent data...")

    missing_datasets = [ ]
    response = JSON.parse(on(master, query).stdout.chomp)

    dataset_states = response.select {|k, v| agent_datasets.include?(k)}.map do |k, v|
      t = Time.iso8601(v) rescue nil
      [k, t]
    end.to_h

    missing_datasets = dataset_states.select {|k, v| v.nil? || (v < run_timestamp)}.keys
    break if missing_datasets.empty?

    sleep(1) # Give PuppetDB some time to catch up.
  end

  assert_empty(missing_datasets, <<-EOS)
PuppetDB did not return updated data for #{master_fqdn} after
#{retries} consecutive queries. The following timestamps were
missing or not updated to be later than: #{run_timestamp.iso8601(3)}:

  #{missing_datasets.join(' ')}

Check puppetserver.log for errors that may have ocurred during
data submission.

Check puppetdb.log for errors that may have ocurred during
data processing.
EOS
end
