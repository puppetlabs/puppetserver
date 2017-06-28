step "Initialize Test Config" do
  PuppetServerExtensions.initialize_config options

  latest_options = [
    [:puppet_build_version, lambda { latest_agent_build }],
    [:puppet_version, lambda { latest_puppet_version }],
    [:puppetdb_build_version, lambda { latest_pdb_build }]
  ]

  latest_options.each do |(option, fn)|
    if PuppetServerExtensions.config[option].upcase == 'LATEST'
      PuppetServerExtensions.config[option] = fn.call
      logger.info "Setting option #{option} to latest version #{PuppetServerExtensions.config[option]}"
    end
  end
  PuppetServerExtensions.print_config
end
