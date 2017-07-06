step "Install PC1 repository" do
  install_puppetlabs_release_repo(hosts, 'pc1')
end

hosts.each { |agent|
  variant = agent['platform'].variant
  if variant == 'sles'
    on(agent, 'rpmkeys --import https://yum.puppetlabs.com/RPM-GPG-KEY-puppet')
  end
}

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
  dir = master.tmpdir(File.basename('/tmp'))

  lay_down_new_puppet_conf( master,
                           {"main" => { "dns_alt_names" => "puppet,#{hostname},#{fqdn}",
                                       "verbose" => true }}, dir)
end

puppetserver_initialize_ssl
