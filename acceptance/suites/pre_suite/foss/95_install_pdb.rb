
matching_puppetdb_platform = puppetdb_supported_platforms.select { |r| r =~ master.platform }
skip_test if matching_puppetdb_platform.length == 0 || master.fips_mode?


test_name 'PuppetDB setup'
sitepp = '/etc/puppetlabs/code/environments/production/manifests/site.pp'

teardown do
  on(master, "rm -f #{sitepp}")
end

# Install the SNAPSHOT with the necessary modifications to the
# puppetdb terminus; once PuppetDB has tagged and released, revert
# this commit and update the `install_puppetlabs_release_repo_on`
# to use 'puppet6'

# step 'Install Puppet Release Repo' do
#   install_puppetlabs_release_repo_on(master, 'puppet5')
# end
install_puppetlabs_dev_repo master, 'puppetdb', "6.4.1.SNAPSHOT.2019.08.13T0822"

step 'Install PuppetDB module' do
  on(master, puppet('module install puppetlabs-puppetdb'))
end

if master.platform.variant == 'debian'
  master.install_package('apt-transport-https')
end

step 'Configure PuppetDB via site.pp' do
  create_remote_file(master, sitepp, <<SITEPP)
node default {
  class { 'puppetdb':
    manage_firewall => false,
  }

  class { 'puppetdb::master::config':
    manage_report_processor => true,
    enable_reports          => true,
  }
}
SITEPP

  on(master, "chmod 644 #{sitepp}")
  with_puppet_running_on(master, {}) do
    on(master, puppet_agent("--test --server #{master}"), :acceptable_exit_codes => [0,2])
  end
end
