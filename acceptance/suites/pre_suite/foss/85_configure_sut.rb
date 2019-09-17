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

step "Use Java 11 on RHEL 8" do
  if master['platform'].start_with?('el-8')
    on master, 'yum install -y java-11'
    on master, 'alternatives --set java java-11-openjdk.x86_64'
    on master, 'systemctl restart puppetserver'
  end
end
