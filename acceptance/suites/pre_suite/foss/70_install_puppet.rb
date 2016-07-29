module CommandUtils
  def ruby_command(host)
    "env PATH=\"#{host['privatebindir']}:${PATH}\" ruby"
  end
  module_function :ruby_command

  def gem_command(host)
    if host['platform'] =~ /windows/
      "env PATH=\"#{host['privatebindir']}:${PATH}\" cmd /c gem"
    else
      "env PATH=\"#{host['privatebindir']}:${PATH}\" gem"
    end
  end
  module_function :gem_command
end

def install_puppet_from_msi( host, opts )
  if not link_exists?(opts[:url])
    raise "Puppet does not exist at #{opts[:url]}!"
  end

  # `start /w` blocks until installation is complete, but needs to be wrapped in `cmd.exe /c`
  on host, "cmd.exe /c start /w msiexec /qn /i #{opts[:url]} /L*V C:\\\\Windows\\\\Temp\\\\Puppet-Install.log"

  # make sure the background service isn't running while the test executes
  on host, "net stop puppet"

  # make sure install is sane, beaker has already added puppet and ruby
  # to PATH in ~/.ssh/environment
  on host, puppet('--version')
  ruby = CommandUtils.ruby_command(host)
  on host, "#{ruby} --version"
end

step "Install MRI Puppet Agents."
  hosts.each do |host|
    platform = host.platform

    puppet_version = test_config[:puppet_version]

    if /windows/.match(platform)
      arch = host[:ruby_arch] || 'x86'
      base_url = ENV['MSI_BASE_URL'] || "http://builds.delivery.puppetlabs.net/puppet-agent/#{test_config[:puppet_build_version]}/artifacts/windows"
      filename = ENV['MSI_FILENAME'] || "puppet-agent-#{arch}.msi"
      install_puppet_from_msi(host, :url => "#{base_url}/#{filename}")
    elsif puppet_version
      install_package host, 'puppet-agent'
    else
      puppet_version = test_config[:puppet_version]

      variant, _, _, _ = host['platform'].to_array

      case variant
      when /^(debian|ubuntu)$/
        puppet_version += "-1puppetlabs1"
        install_package host, "puppet-agent=#{puppet_version}"
      when /^(redhat|el|centos)$/
        install_package host, 'puppet-agent', puppet_version
      end

    end

  end

step "Upgrade nss to version that is hopefully compatible with jdk version puppetserver will use." do
  nss_package=nil
  variant, _, _, _ = master['platform'].to_array
  case variant
  when /^(debian|ubuntu)$/
    nss_package_name="libnss3"
  when /^(redhat|el|centos)$/
    nss_package_name="nss"
  end
  if nss_package_name
    master.upgrade_package(nss_package_name)
  else
    logger.warn("Don't know what nss package to use for #{variant} so not installing one")
  end
end

if (test_config[:puppetserver_install_mode] == :upgrade)
  step "Upgrade Puppet Server."
    upgrade_package(master, "puppetserver")
else
  step "Install Puppet Server."
    make_env = {
      "prefix" => "/usr",
      "confdir" => "/etc/",
      "rundir" => "/var/run/puppetserver",
      "initdir" => "/etc/init.d",
    }
    install_puppet_server master, make_env
end
