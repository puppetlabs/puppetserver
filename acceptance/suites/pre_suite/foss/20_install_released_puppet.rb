# skip this step entirely unless we are running in :upgrade mode
if (test_config[:puppetserver_install_mode] == :upgrade)
  step "Install released MRI Puppet for upgrade test" do
    install_package master, 'puppet'
  end

  step "Run puppet as puppet user to prevent permissions errors later." do
    puppet_apply_as_puppet_user
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
