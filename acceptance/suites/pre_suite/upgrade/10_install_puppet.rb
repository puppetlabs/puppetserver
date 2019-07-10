def install_pc1_repo(host)
  variant, version, _, codename = host['platform'].to_array
  return if variant =~ /win/

  variant = 'el' if variant =~ /redhat|centos/
  debian = !!codename

  if debian
    pkg = "puppetlabs-release-pc1-#{codename}.deb"
    url = "http://release-archives.puppet.com/apt"
  else
    pkg = "puppetlabs-release-pc1-#{variant}-#{version}.noarch.rpm"
    url = "http://release-archives.puppet.com/yum"
  end

  on host, "wget #{url}/#{pkg}"

  if debian
    on host, "dpkg -i --force-all #{pkg}"
    on host, "apt-get update"
  else
    on host, "rpm -i --replacepkgs #{pkg}"
  end

  if variant =~ /sles/
    # Hack around RE-12490's invalid sles repo lists for pc1.
    on host, "sed -i 's,==,=,' /etc/zypp/repos.d/puppetlabs-pc1.repo"
    on host, "rpmkeys --import https://yum.puppetlabs.com/RPM-GPG-KEY-puppet"
  end

  # cargo culted
  configure_type_defaults_on(host)
end



step "Install PC1 repository" do
  block_on(hosts) do |host|
    install_pc1_repo(host)
  end
end

step "Install legacy Puppet agents" do
  default_puppet_version = '1.10.1'
  puppet_version = ENV['PUPPET_LEGACY_VERSION']
  if not puppet_version
    logger.info "PUPPET_LEGACY_VERSION is not set!"
    logger.info "  using default value of #{default_puppet_version}"
    puppet_version = default_puppet_version
  end

  hosts.each { |host| host.install_package('puppet-agent') }
end

step "Install legacy Puppetserver" do
  master.install_package('puppetserver')
end

step "Configure puppet.conf" do
  hostname = on(master, 'facter hostname').stdout.strip
  fqdn = on(master, 'facter fqdn').stdout.strip

  hosts.each do |host|
    dir = host.tmpdir('configure_puppet')

    if host == master
      lay_down_new_puppet_conf( host,
                               {"main" => {"dns_alt_names" => "puppet,#{hostname},#{fqdn}",
                                           "verbose" => true,
                                           "server" => fqdn}}, dir)
    else
      lay_down_new_puppet_conf(host, {"main" => {"server" => fqdn}}, dir)
    end
  end
end

puppetserver_initialize_ssl
