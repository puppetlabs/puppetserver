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
      master['curl-retries'] = 1500
      with_puppet_running_on(master, "main" => { "dns_alt_names" => "puppet,#{hostname},#{fqdn}", "verbose" => true, "daemonize" => true }) do

        hosts.each do |host|
          next if host['roles'].include? 'master'

          step "Agents: Run agent --test first time to gen CSR"
          on host, puppet("agent --test --server #{master}"), :acceptable_exit_codes => [0]
        end

      end
      master['curl-retries'] = 120
  end

  # Obtained from:
  #   https://github.com/puppetlabs/classifier/blob/master/integration/helper.rb#L752
  #
  def fetch(base_url, file_name, dst_dir)
    FileUtils.makedirs(dst_dir)
    src = "#{base_url}/#{file_name}"
    dst = File.join(dst_dir, file_name)
    if File.exists?(dst)
      logger.notify "Already fetched #{dst}"
    else
      logger.notify "Fetching: #{src}"
      logger.notify "  and saving to #{dst}"
      open(src) do |remote|
        File.open(dst, "w") do |file|
          FileUtils.copy_stream(remote, file)
        end
      end
    end
    return dst
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

  # Obtained from:
  #   https://github.com/puppetlabs/classifier/blob/master/integration/helper.rb#L819
  # With minor semantic changes.
  #
  def install_dev_repos_on(package, host, build_version, repo_configs_dir)
    platform = host['platform']
    platform_configs_dir = File.join(repo_configs_dir, platform)

    case platform
      when /^(fedora|el|centos)-(\d+)-(.+)$/
        variant = (($1 == 'centos') ? 'el' : $1)
        fedora_prefix = ((variant == 'fedora') ? 'f' : '')
        version = $2
        arch = $3

        pattern = "pl-%s-%s-%s-%s%s-%s.repo"
        repo_filename = pattern % [
          package,
          build_version,
          variant,
          fedora_prefix,
          version,
          arch
        ]

        repo = fetch(
          "http://builds.puppetlabs.lan/%s/%s/repo_configs/rpm/" % [package, build_version],
          repo_filename,
          platform_configs_dir
        )

        scp_to(host, repo, '/etc/yum.repos.d/')

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

        list = fetch(
          "http://builds.puppetlabs.lan/%s/%s/repo_configs/deb/" % [package, build_version],
          "pl-%s-%s-%s.list" % [package, build_version, codename],
          platform_configs_dir
        )

        scp_to host, list, '/etc/apt/sources.list.d'
        on host, 'apt-get update'
      else
        host.logger.notify("No repository installation step for #{platform} yet...")
    end
  end

end

Beaker::TestCase.send(:include, JVMPuppetExtensions)
