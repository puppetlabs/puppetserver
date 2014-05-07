def initialize_repos_on_host(host)
  if host['platform'].include? 'el-6'
    on host, "rpm -ivh https://yum.puppetlabs.com/el/6/products/x86_64/puppetlabs-release-6-7.noarch.rpm"
    on host, "sed -i.bak -e '/\\[puppetlabs-devel\\]/,/^$/ s|enabled=0|enabled=1|' /etc/yum.repos.d/puppetlabs.repo"
    on host, "curl #{ENV['JVMPUPPET_REPO_CONFIG']} > /etc/yum.repos.d/jvmpuppet-puppetlabs.repo"
  else
    raise ArgumentError, "Unsupported OS '#{host['platform']}'"
  end
end

step "Setup Puppet Labs repositories." do
  hosts.each do |host|
    initialize_repos_on_host(host)
  end
end
