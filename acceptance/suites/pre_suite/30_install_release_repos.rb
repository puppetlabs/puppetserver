case test_config[:jvmpuppet_install_type]
when :package
  step "Setup JVM Puppet repositories." do
    package_build_version = ENV['PACKAGE_BUILD_VERSION']
    if package_build_version
      install_dev_repos_on 'jvm-puppet', master, package_build_version, "repo_configs"
    else
      abort("Environment variable PACKAGE_BUILD_VERSION required for package installs!")
    end
  end
end

step "Setup Puppet Labs Release repositories." do
  hosts.each do |host|
    install_release_repos_on host
  end
end
