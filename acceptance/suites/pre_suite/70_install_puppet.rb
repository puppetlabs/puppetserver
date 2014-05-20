step "Install MRI Puppet Agents."
  puppet_version = ENV["PUPPET_VERSION"]
  hosts.each do |host|
    custom_install_puppet_package host, 'puppet', puppet_version, true
  end

step "Install JVM Puppet Master."
  custom_install_puppet_package master, 'jvm-puppet'
