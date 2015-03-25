confine :to, :platform => ['windows']

step "Setup Puppet Labs Release repositories on windows nodes" do
  hosts.each do |host|
    install_puppetlabs_release_repo host
  end
end
