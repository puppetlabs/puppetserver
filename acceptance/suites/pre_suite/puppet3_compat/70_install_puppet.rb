# Agent running on the master is current, not legacy.
legacy_agents = agents.reject { |agent| agent == master }

step "Install Legacy Puppet Agents."
legacy_agents.each do |host|
  platform = host.platform

  if not ENV['PUPPET_LEGACY_VERSION']
    fail "PUPPET_LEGACY_VERSION is not set, e.g. '3.7.5'"
  else
    if platform =~ /(ubuntu|debian|cumulus)/
      # Recommendation from QE in QENG-2385
      puppet_version = "#{ENV['PUPPET_LEGACY_VERSION']}-1puppetlabs1"
    else
      puppet_version = ENV['PUPPET_LEGACY_VERSION']
    end
  end
  install_package host, 'puppet', puppet_version
end

step "Install Puppet Server."
install_puppet_server master
