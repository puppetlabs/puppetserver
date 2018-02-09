step "Initialize Test Config" do
  PuppetServerExtensions.initialize_config options

  PuppetServerExtensions.print_config
end
