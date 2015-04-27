# Agent running on the master is current, not legacy.
legacy_agents = agents.reject { |agent| agent == master }

step "Install Legacy Puppet Agents."
legacy_agents.each do |host|
  platform = host.platform

  puppet_version = ENV['PUPPET_LEGACY_VERSION']
  if not puppet_version
    fail "PUPPET_LEGACY_VERSION is not set, e.g. '3.7.5'"
  end
  install_package host, 'puppet', puppet_version
end

step "Install Puppet Server."
make_env = {
  "prefix"  => "/usr",
  "confdir" => "/etc/",
  "rundir"  => "/var/run/puppetserver",
  "initdir" => "/etc/init.d",
}
install_puppet_server master, make_env
