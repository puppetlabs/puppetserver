case test_config[:puppetserver_install_type]
when :git # only if installing from source
  step "Install Puppet Server Dependencies"
    `lein install`
    project_version = 'puppetserver-version='
    project_version += test_config[:puppetserver_version] ||
      `lein with-profile ci pprint :version | tail -n 1 | cut -d\\" -f2`
    ezbake_stage 'puppetserver', project_version

    install_ezbake_deps master
end
