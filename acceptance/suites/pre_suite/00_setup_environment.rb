step "Determine host OS's"
  os_families = hosts.inject({}) do |result, host|
    result[host.name] = get_os_family(host)
    result
  end

step "Initialize Test Config"
  JVMPuppetExtensions.initialize_config(options, os_families)
