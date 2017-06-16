step "Initialize Test Config" do
  PuppetServerExtensions.initialize_config options

  if PuppetServerExtensions.config[:puppet_build_version] == 'LATEST'
    PuppetServerExtensions.config[:puppet_build_version] = latest_agent_build
    logger.info "Using latest Puppet agent build version (#{PuppetServerExtensions.config[:puppet_build_version]})"
  end

  if PuppetServerExtensions.config[:puppetdb_build_version] == 'LATEST'
    PuppetServerExtensions.config[:puppetdb_build_version] = latest_pdb_build
    logger.info "Using latest PuppetDB build version (#{PuppetServerExtensions.config[:puppetdb_build_version]})"
  end
end
