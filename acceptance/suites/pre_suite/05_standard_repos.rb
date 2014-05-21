step "Setup JVM Puppet repositories." do
  hosts.each do |host|
    package_build_version = ENV['PACKAGE_BUILD_VERSION']
    if package_build_version
      install_dev_repos_on 'jvm-puppet', host, package_build_version, "repo_configs"
    else
      abort("Missing required PACKAGE_BUILD_VERSION!")
    end
  end
end

step "Setup Puppet Labs Release repositories." do
  hosts.each do |host|
    install_release_repos_on host
  end
end
