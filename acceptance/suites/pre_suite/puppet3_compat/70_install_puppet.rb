# Agent running on the master is current, not legacy.
legacy_agents = agents.reject { |agent| agent == master }

step "Install Legacy Puppet Agents."

tmp_hosts = hosts
legacy_agents.each do |host|
  platform = host.platform

  puppet_version = ENV['PUPPET_LEGACY_VERSION']
  if not puppet_version
    fail "PUPPET_LEGACY_VERSION is not set, e.g. '3.7.5'"
  end
  hosts = [host]
  install_puppet({:version => puppet_version})
end
hosts = tmp_hosts

step "Install Puppet Server."
install_puppet_server master
