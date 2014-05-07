def initialize_repos_on_host(host)
  if host['platform'].include? 'el-6'
    el6_repo_config = "http://builds.puppetlabs.lan/puppet/3.6.0-jvm-puppet-preview1/repo_configs/rpm/pl-puppet-3.6.0-jvm-puppet-preview1-el-6-x86_64.repo"
    on host, "curl #{el6_repo_config} > /etc/yum.repos.d/special-puppetlabs.repo"
  else
    raise ArgumentError, "Unsupported OS '#{host['platform']}'"
  end
end

step "Setup Puppet Labs repositories." do
  hosts.each do |host|
    initialize_repos_on_host(host)
  end
end
