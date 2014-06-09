step "Install MRI Puppet Agents."
  puppet_version = ENV["PUPPET_VERSION"]
  hosts.each do |host|
    install_package_version host, 'puppet', puppet_version
  end

step "Install JVM Puppet Master."
  install_jvm_puppet master
