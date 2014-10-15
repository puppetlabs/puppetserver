step "Install MRI Puppet Agents."
  hosts.each do |host|
    puppet_version = test_config[:puppet_version]

    if puppet_version
      install_package host, 'puppet'
    else
      puppet_version = test_config[:puppet_version]

      variant, _, _, _ = host['platform'].to_array

      case variant
      when /^(debian|ubuntu)$/
        puppet_version += "-1puppetlabs1"
        install_package host, "puppet=#{puppet_version} puppet-common=#{puppet_version}"
      when /^(redhat|el|centos)$/
        install_package host, 'puppet', puppet_version
      end

    end

  end

step "Run puppet as puppet user to prevent permissions errors later."
  # When puppet apply is run for the first time, certain directories are created
  # as the user owning the process. Later in this presuite we need to run puppet
  # apply as root. So in order to avoid creating these directories as root (making
  # them inaccessible to the puppet user) we must first run puppet apply as the
  # puppet user here.
  manifest_path = master.tmpfile("puppetserver_manifest.pp")
  herp_path = master.tmpfile("herp")

  manifest_content = <<-EOS
  file { "herp":
    path => '#{herp_path}',
    ensure => 'present',
    content => 'derp',
  }
  EOS

  user = master.puppet('master')['user']
  create_remote_file(master, manifest_path, manifest_content)
  on master, "chown #{user}:#{user} #{manifest_path}"

  on master, "su -s /bin/bash -c \"puppet apply #{manifest_path}\" #{user}"

step "Install Puppet Server."
  make_env = {
    "prefix" => "/usr",
    "confdir" => "/etc/",
    "rundir" => "/var/run/puppetserver",
    "initdir" => "/etc/init.d",
  }
  install_puppet_server master, 'puppetserver', make_env
