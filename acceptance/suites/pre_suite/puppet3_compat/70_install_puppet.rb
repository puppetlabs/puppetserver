require 'puppetserver/acceptance/compat_utils'

step "Install Legacy Puppet Agents."

puppet_version = ENV['PUPPET_LEGACY_VERSION']
if not puppet_version
  fail "PUPPET_LEGACY_VERSION is not set, e.g. '3.7.5'"
end

install_puppet_on(nonmaster_agents, {:version => puppet_version})

variant = master['platform'].variant
version = master['platform'].version
if variant == 'debian' && version == "8"
  create_remote_file(master, "/etc/apt/sources.list.d/jessie-backports.list", "deb http://ftp.debian.org/debian jessie-backports main")
  on master, 'apt-get update'
  master.install_package("openjdk-8-jre-headless", "-t jessie-backports")
end

step "Install Puppet Server."
install_puppet_server master
