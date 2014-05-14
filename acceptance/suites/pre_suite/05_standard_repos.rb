def initialize_repos_on_host(host)
  repo_config = ENV['JVMPUPPET_REPO_CONFIG']
  if host['platform'].include? 'el-6'
    on host, "rpm -ivh https://yum.puppetlabs.com/el/6/products/x86_64/puppetlabs-release-6-7.noarch.rpm"
    on host, "curl #{repo_config} > /etc/yum.repos.d/jvmpuppet-puppetlabs.repo"
  elsif host['platform'].include? 'debian'
    platform_name = ENV['PLATFORM_NAME']
    on host, "wget -O /tmp/puppet.deb https://apt.puppetlabs.com/puppetlabs-release-#{platform_name}.deb"
    on host, "dpkg -i /tmp/puppet.deb"
    on host, "wget -O /etc/apt/sources.list.d/jvm-puppet-#{platform_name}.list #{repo_config}"
    on host, "apt-get update"
  else
    raise ArgumentError, "Unsupported OS '#{host['platform']}'"
  end
end

step "Setup Puppet Labs repositories." do
  hosts.each do |host|
    initialize_repos_on_host(host)
  end
end
