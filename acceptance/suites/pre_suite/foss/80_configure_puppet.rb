step "Configure puppet.conf" do
  hostname = on(master, 'facter hostname').stdout.strip
  fqdn = on(master, 'facter fqdn').stdout.strip
  dir = master.tmpdir(File.basename('/tmp'))

  lay_down_new_puppet_conf( master,
                           {"main" => {"dns_alt_names" => "puppet,#{hostname},#{fqdn}",
                                       "verbose" => true,
                                       "server" => fqdn}}, dir)

  config = { 'certificate-authority' => { 'allow-subject-alt-names' => true }}
  path = '/etc/puppetlabs/puppetserver/conf.d/puppetserver.conf'
  modify_tk_config(master, path, config)

end
