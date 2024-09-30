
matching_puppetdb_platform = puppetdb_supported_platforms.select { |r| r =~ master.platform }
skip_test if matching_puppetdb_platform.length == 0 || master.fips_mode?


test_name 'PuppetDB setup'
sitepp = '/etc/puppetlabs/code/environments/production/manifests/site.pp'

teardown do
  on(master, "rm -f #{sitepp}")
end

step 'Install Puppet nightly repo' do
  install_puppetlabs_release_repo_on(master, 'puppet8-nightly')
end

step 'Update EL postgresql repos' do
  # work around for testing on rhel and the repos on the image not finding the pg packages it needs
  if master.platform =~ /el-/
    major_version = case master.platform
      when /-8/ then 8
      when /-9/ then 9
      end

    on master, "dnf install -y https://download.postgresql.org/pub/repos/yum/reporpms/EL-#{major_version}-x86_64/pgdg-redhat-repo-latest.noarch.rpm"
    on master, "dnf -qy module disable postgresql"
  end
end

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
    manage_firewall     => false,
    manage_package_repo => true,
    postgres_version    => '14',
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
