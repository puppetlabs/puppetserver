# skip this step entirely unless we are running in :upgrade mode
if (test_config[:puppetserver_install_mode] == :upgrade)
  step "Install released MRI Puppet for upgrade test" do
    hosts.each do |host|
      install_package host, 'puppet'
    end
  end

  step "Install released Puppet Server for upgrade test" do
    manifest = "
    package { 'puppetserver':
      ensure => 'installed'
    }
    "
    apply_manifest_on(master, manifest)
    puppetserver_initialize_ssl
  end
end
