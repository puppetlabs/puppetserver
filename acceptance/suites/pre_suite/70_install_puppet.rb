step "Install JVM Puppet Master."
  if master['platform'].include? 'debian'
    on master, 'apt-get install --force-yes -y jvm-puppet'
  else
    install_package master, 'jvm-puppet'
  end


step "Install Puppet Agents."
  agents.each do |agent|
    install_package agent, 'puppet'
  end

