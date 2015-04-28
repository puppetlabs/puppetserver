confine :except, :platform => ['windows']

step "Setup Puppet Labs Release repositories" do
  hosts.each do |host|
    install_puppetlabs_release_repo host
  end
end
