step "Setup JVM Puppet repositories." do
  hosts.each do |host|
    install_jvmpuppet_repos_on host
  end
end

step "Setup Puppet Labs Release repositories." do
  hosts.each do |host|
    install_release_repos_on host
  end
end
