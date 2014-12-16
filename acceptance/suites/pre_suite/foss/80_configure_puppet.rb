step "Configure puppet.conf" do
  hostname = on(master, 'facter hostname').stdout.strip
  fqdn = on(master, 'facter fqdn').stdout.strip
  dir = master.tmpdir(File.basename('/tmp'))

  lay_down_new_puppet_conf( master,
                           {"main" => { "dns_alt_names" => "puppet,#{hostname},#{fqdn}",
                                       "verbose" => true }}, dir)
end

case test_config[:puppetserver_install_type]
when :git
  step "Configure os-settings.conf" do
    configure_puppet_server
  end
end
