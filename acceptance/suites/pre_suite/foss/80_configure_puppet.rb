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
