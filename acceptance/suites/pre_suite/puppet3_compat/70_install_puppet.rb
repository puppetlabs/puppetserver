# Agent running on the master is current, not legacy.
legacy_agents = agents.reject { |agent| agent == master }

step "Install Legacy Puppet Agents."
legacy_agents.each do |host|
  platform = host.platform
  if not ENV['PUPPET_LEGACY_VERSION']
    fail "PUPPET_LEGACY_VERSION is not set, e.g. '3.7.5'"
  else
    puppet_version = ENV['PUPPET_LEGACY_VERSION']
    default_download_url = 'http://downloads.puppetlabs.com'
    opts = {:win_download_url => "#{default_download_url}/windows",
            :mac_download_url => "#{default_download_url}/mac",
            :version => puppet_version}
    if host['platform'] =~ /el-(5|6|7)/
      relver = $1
      install_puppet_from_rpm host, opts.merge(:release => relver, :family => 'el')
    elsif host['platform'] =~ /fedora-(\d+)/
      relver = $1
      install_puppet_from_rpm host, opts.merge(:release => relver, :family => 'fedora')
    elsif host['platform'] =~ /(ubuntu|debian|cumulus)/
      install_puppet_from_deb host, opts
    elsif host['platform'] =~ /windows/
      relver = opts[:version]
      install_puppet_from_msi host, opts
    else
      raise "unsupported platform '#{host['platform']}' on '#{host.name}'"
    end
  end
end

step "Install Puppet Server."
install_puppet_server master
