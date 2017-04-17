step "Configure puppet.conf" do
  hostname = on(master, 'facter hostname').stdout.strip
  fqdn = on(master, 'facter fqdn').stdout.strip
  dir = master.tmpdir(File.basename('/tmp'))

  hosts.each do |host|
    next if host['roles'].include? 'master'
    dir = host.tmpdir(File.basename('/tmp'))
    lay_down_new_puppet_conf( master,
                           {"main" => { "http_compression" => true }}, dir)
  end

  lay_down_new_puppet_conf( master,
                           {"main" => { "dns_alt_names" => "puppet,#{hostname},#{fqdn}",
                                        "http_compression" => true,
                                       "verbose" => true }}, dir)
end
