step "Prevent master nodes from checking in with dujour" do
  manifest_path = master.tmpfile("puppetserver_manifest.pp")

  manifest_content = <<-EOS
    host { "updates.puppetlabs.com":
    ip => '127.0.0.1',
    ensure => 'present',
  }
  EOS

  create_remote_file(master, manifest_path, manifest_content)

  on master, puppet_apply("#{manifest_path}")
end
