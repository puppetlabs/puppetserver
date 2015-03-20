step "Setup Puppet Labs Release repositories." do
  hosts.each do |host|
    platform = host.platform
    if not /windows/.match(platform)
      install_puppetlabs_release_repo host
    end
  end
end
