case test_config[:jvmpuppet_install_type]
when :git # only if installing from source
  step "Install JVM Puppet Dependencies"
    project_version = test_config[:jvmpuppet_version] ||
      `lein with-profile acceptance pprint :version | cut -d\\" -f2`
    ezbake_stage 'jvm-puppet', project_version
    install_ezbake_deps master

    # now install other deps
    install_package master, 'java-1.7.0-openjdk'
end
