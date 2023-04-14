
matching_puppetdb_platform = puppetdb_supported_platforms.select { |r| r =~ master.platform }
skip_test if matching_puppetdb_platform.length == 0 || master.fips_mode?


test_name 'PuppetDB setup'
sitepp = '/etc/puppetlabs/code/environments/production/manifests/site.pp'

teardown do
  on(master, "rm -f #{sitepp}")
end

# Puppet pulls in OpenSSL 3 which breaks ssl-cert < 1.1.1
# Unfortunately we need jammy to bring a workable version of ssl-cert into bionic
step 'Update Ubuntu 18 package repo' do
  if master.platform =~ /ubuntu-18/
    # There's a bunch of random crap that gets upgraded in our installs,
    # just upgrade everything before we try to install postgres
    on master, 'apt-get update'
    on master, 'DEBIAN_FRONTEND=noninteractive apt-get upgrade --assume-yes --force-yes -o "DPkg::Options::=--force-confold"'
    # Install jammy repos so we can pull in its ssl-cert
    on master, "echo 'deb http://archive.ubuntu.com/ubuntu/ jammy main restricted universe multiverse' > /etc/apt/sources.list.d/jammy.list"
    on master, "echo 'deb-src http://archive.ubuntu.com/ubuntu/ jammy main restricted universe multiverse' >> /etc/apt/sources.list.d/jammy.list"
    on master, 'apt-get update'
    on master, 'apt-get install -y -t jammy ssl-cert'
    # Once we have jammy's ssl-cert get rid of jammy packages to avoid unintentially pulling in other packages
    on master, 'rm /etc/apt/sources.list.d/jammy.list'
    on master, 'apt-get update'
  end
end

step 'Install Puppet nightly repo' do
  install_puppetlabs_release_repo_on(master, 'puppet8-nightly')
end

step 'Update EL 8 postgresql repos' do
  if master.platform =~ /el-8/
    # work around for testing on rhel8 and the repos on the image not finding the pg packages it needs
    on master, "dnf install -y https://download.postgresql.org/pub/repos/yum/reporpms/EL-8-x86_64/pgdg-redhat-repo-latest.noarch.rpm"
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
