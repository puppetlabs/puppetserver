step "Setup access to temporary/special Puppet Labs repositories." do
  repo_config = ENV['JVMPUPPET_REPO_CONFIG']
  hosts.each do |host|
    if host['platform'].include? 'el-6'
      on host, "sed -i.bak -e '/\\[puppetlabs-devel\\]/,/^$/ s|enabled=0|enabled=1|' /etc/yum.repos.d/puppetlabs.repo"
    end
  end
end

