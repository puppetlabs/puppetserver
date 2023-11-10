
matching_puppetdb_platform = puppetdb_supported_platforms.select { |r| r =~ master.platform }
skip_test if matching_puppetdb_platform.length == 0 || master.fips_mode?


test_name 'PuppetDB setup'
sitepp = '/etc/puppetlabs/code/environments/production/manifests/site.pp'

teardown do
  on(master, "rm -f #{sitepp}")
end

step 'Update Ubuntu 18 package repo' do
  if master.platform =~ /ubuntu-18/
    # bionic is EOL, so get postgresql from the archive
    on master, 'echo "deb https://apt-archive.postgresql.org/pub/repos/apt bionic-pgdg main" >> /etc/apt/sources.list'
    on master, 'curl https://www.postgresql.org/media/keys/ACCC4CF8.asc | sudo apt-key add -'
    on master, 'apt update'
  end
end

step 'Install Puppet nightly repo' do
  install_puppetlabs_release_repo_on(master, 'puppet7-nightly')
end

step 'Install PuppetDB module' do
  on(master, puppet('module install puppetlabs-puppetdb'))
end

if master.platform.variant == 'debian'
  master.install_package('apt-transport-https')
end

step 'Configure PuppetDB via site.pp' do
  manage_package_repo = ! master.platform.match?(/ubuntu-18/)
  create_remote_file(master, sitepp, <<SITEPP)
node default {
  class { 'puppetdb':
    manage_firewall     => false,
    manage_package_repo => #{manage_package_repo},
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
