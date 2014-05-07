step "Install JVM Puppet Master." 
  install_package master, 'jvm-puppet'


step "Install Puppet Agents." 
  agents.each do |agent|
    install_package agent, 'puppet'
  end

