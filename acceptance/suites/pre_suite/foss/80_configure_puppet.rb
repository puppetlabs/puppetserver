step "Configure puppet.conf" do
  hostname = on(master, 'facter hostname').stdout.strip
  fqdn = on(master, 'facter fqdn').stdout.strip
  dir = master.tmpdir(File.basename('/tmp'))

  lay_down_new_puppet_conf( master,
                           {"main" => { "dns_alt_names" => "puppet,#{hostname},#{fqdn}",
                                       "verbose" => true,
                                       "certname" => "#{master}" }}, dir)

  case master['platform']
  when /^(fedora|el|centos)-(\d+)-(.+)$/
    defaults_file = '/etc/sysconfig/jvm-puppet'
  when /^(debian|ubuntu)-([^-]+)-(.+)$/
    defaults_file = '/etc/default/jvm-puppet'
  else
    host.logger.notify("Not sure how to handle defaults for #{platform} yet...")
  end
  on master, "sed -i -e 's/\(SERVICE_NUM_RETRIES\)=[0-9]*/\1=60/' #{defaults_file}"
end
