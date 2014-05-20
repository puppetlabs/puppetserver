require 'beaker/dsl/install_utils'

module JVMPuppetExtensions

  def initialize_ssl
    hostname = on(master, 'facter hostname').stdout.strip
    fqdn = on(master, 'facter fqdn').stdout.strip

    step "Clear SSL on all hosts"
    hosts.each do |host|
      ssldir = on(host, puppet('agent --configprint ssldir')).stdout.chomp
      on(host, "rm -rf '#{ssldir}'")
    end

    step "Master: Start Puppet Master"
      with_puppet_running_on(master, "main" => { "dns_alt_names" => "puppet,#{hostname},#{fqdn}", "verbose" => true, "daemonize" => true }) do

        hosts.each do |host|
          next if host['roles'].include? 'master'

          step "Agents: Run agent --test first time to gen CSR"
          on host, puppet("agent --test --server #{master}"), :acceptable_exit_codes => [0]
        end

      end
  end

  def custom_install_puppet_package (host, package_name, package_version="", noarch=false)
    platform = host['platform']

    case platform
    when /^(fedora|el|centos)-(\d+)-(.+)$/
      variant = (($1 == 'centos')? 'el' : $1)
      version = $2
      arch = $3

      if noarch
        arch = "noarch"
      end

      if package_version != ""
        package_name = "#{package_name}-#{package_version}.#{variant}#{version}.#{arch}"
      end

      install_package host, package_name

    when /^(debian|ubuntu)-([^-]+)-(.+)$/
      variant = (($1 == 'centos')? 'el' : $1)
      version = $2
      #arch = $3

      if package_version != ""
        package_name = "#{package_name}=#{package_version}puppetlabs1"
      end

      on host, "apt-get install --force-yes -y #{package_name}"

    else
      raise ArgumentError, "No repository installation step for #{platform} yet..."
    end
  end

  def get_debian_codename(version)
    case version
    when /^6$/
      return "squeeze"
    when /^7$/
      return "wheezy"
    end
  end

  def get_ubuntu_codename(version)
    case version
    when /^1004$/
      return "lucid"
    when /^1204$/
      return "precise"
    end
  end

  def install_release_repos_on(host)
    platform = host['platform']

    case platform
      when /^(fedora|el|centos)-(\d+)-(.+)$/
        variant = (($1 == 'centos') ? 'el' : $1)
        version = $2
        arch = $3

        # need to get the release minor version into platform name
        rpm_name = "puppetlabs-release-#{version}-7.noarch.rpm"
        repo_url = "https://yum.puppetlabs.com"

        on host,
          "rpm -ivh #{repo_url}/#{variant}/#{version}/products/#{arch}/#{rpm_name}"

      when /^(debian|ubuntu)-([^-]+)-(.+)$/
        variant = $1
        version = $2
        arch = $3

        case variant
        when /^debian$/
          codename = get_debian_codename(version)
        when /^ubuntu$/
          codename = get_ubuntu_codename(version)
        end

        deb_name = "puppetlabs-release-#{codename}.deb"
        repo_url = "https://apt.puppetlabs.com"

        on host, "wget -O /tmp/puppet.deb #{repo_url}/#{deb_name}"
        on host, "dpkg -i --force-all /tmp/puppet.deb"

        on host, 'apt-get update'
      else
        host.logger.notify("No repository installation step for #{platform} yet...")
    end
  end

  # might move repo_config generation here in the future
  def install_jvmpuppet_repos_on(host)
    platform = host['platform']
    repo_config = ENV['JVMPUPPET_REPO_CONFIG']

    case platform
    when /^(fedora|el|centos)-(\d+)-(.+)$/
      variant = (($1 == 'centos')? 'el' : $1)
      version = $2
      arch = $3
      on host, "curl #{repo_config} > /etc/yum.repos.d/jvmpuppet-#{variant}-#{version}-#{arch}.repo"
      #on host, "yum update"

    when /^(debian|ubuntu)-([^-]+)-(.+)$/
      # might move repo_config generation here in the future
      variant = (($1 == 'centos')? 'el' : $1)
      version = $2
      #arch = $3
      on host, "wget -O /etc/apt/sources.list.d/jvm-puppet-#{variant}-#{version}.list #{repo_config}"
      on host, "apt-get update"

    else
      raise ArgumentError, "No repository installation step for #{platform} yet..."
    end
  end
end

Beaker::TestCase.send(:include, JVMPuppetExtensions)
