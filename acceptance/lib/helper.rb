require 'beaker/dsl/install_utils'

module JVMPuppetExtensions

  # Configuration code largely obtained from:
  # https://github.com/puppetlabs/classifier/blob/master/integration/helper.rb
  #
  def self.initialize_config(options)
    base_dir = File.join(File.dirname(__FILE__), '..')

    install_type = get_option_value(options[:jvmpuppet_install_type],
      [:git, :package], "install type", "JVMPUPPET_INSTALL_TYPE", :package)

    install_mode =
        get_option_value(options[:jvmpuppet_install_mode],
                         [:install, :upgrade], "install mode",
                         "JVMPUPPET_INSTALL_MODE", :install)

    jvmpuppet_version =
        get_option_value(options[:jvmpuppet_version],
                         nil, "JVM Puppet Version",
                         "JVMPUPPET_VERSION", nil)
    @config = {
      :base_dir => base_dir,
      :jvmpuppet_install_type => install_type,
      :jvmpuppet_install_mode => install_mode,
      :jvmpuppet_version => jvmpuppet_version,
    }

    pp_config = PP.pp(@config, "")

    Beaker::Log.notify "JVMPuppet Acceptance Configuration:\n\n#{pp_config}\n\n"
  end

  class << self
    attr_reader :config
    attr_accessor :ezbake
  end

  # Return the configuration hash initialized by
  # JVMPuppetExtensions.initialize_config
  #
  def test_config
    JVMPuppetExtensions.config
  end

  # Return the ezbake config.
  #
  def ezbake_config
    JVMPuppetExtensions.ezbake
  end

  def self.get_option_value(value, legal_values, description,
    env_var_name = nil, default_value = nil)

    # precedence is environment variable, option file, default value
    value = ((env_var_name && ENV[env_var_name]) || value || default_value)
    if value
      value = value.to_sym
    end

    unless legal_values.nil? or legal_values.include?(value)
      raise ArgumentError, "Unsupported #{description} '#{value}'"
    end

    value
  end

  def initialize_ssl
    hostname = on(master, 'facter hostname').stdout.strip
    fqdn = on(master, 'facter fqdn').stdout.strip

    step "Clear SSL on all hosts"
    hosts.each do |host|
      ssldir = on(host, puppet('agent --configprint ssldir')).stdout.chomp
      on(host, "rm -rf '#{ssldir}'")
    end

    step "Master: Start Puppet Master"
      old_retries = master['curl-retries']
      master['curl-retries'] = 1500
      with_puppet_running_on(master, "main" => { "dns_alt_names" => "puppet,#{hostname},#{fqdn}", "verbose" => true, "daemonize" => true }) do

        hosts.each do |host|
          next if host['roles'].include? 'master'

          step "Agents: Run agent --test first time to gen CSR"
          on host, puppet("agent --test --server #{master}"), :acceptable_exit_codes => [0]
        end

      end
      master['curl-retries'] = old_retries
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

  def custom_install_puppet_package (host, package_name, package_version=nil, noarch=false)
    platform = host['platform']

    case platform
    when /^(fedora|el|centos)-(\d+)-(.+)$/
      variant = (($1 == 'centos')? 'el' : $1)
      version = $2
      arch = $3

      if noarch
        arch = "noarch"
      end

      if package_version
        package_name = "#{package_name}-#{package_version}.#{variant}#{version}.#{arch}"
      end

      install_package host, package_name

    when /^(debian|ubuntu)-([^-]+)-(.+)$/
      variant = $1
      version = $2
      #arch = $3

      if package_version
        package_name = "#{package_name}=#{package_version}"
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

  def conditionally_clone(upstream_uri, local_path)
    if system "git --work-tree=#{local_path} --git-dir=#{local_path}/.git status"
      system "git --work-tree=#{local_path} --git-dir=#{local_path}/.git pull"
    else
      system "git clone #{upstream_uri} #{local_path}"
    end
  end

  def ezbake_stage(project_name, project_version, ezbake_dir="tmp/ezbake")
    conditionally_clone "git@github.com:puppetlabs/ezbake.git", ezbake_dir

    package_version = ''
    Dir.chdir(ezbake_dir) do
      `lein run -- stage #{project_name} #{project_name}-version=#{project_version}`
    end

    staging_dir = File.join(ezbake_dir, 'target/staging')
    Dir.chdir(staging_dir) do
      output = `rake package:bootstrap`
      load 'ezbake.rb'
      ezbake = EZBake::Config
      ezbake[:package_version] = `echo -n $(rake pl:print_build_param[ref] | tail -n 1)`
      JVMPuppetExtensions.ezbake = ezbake
    end
  end

  def install_ezbake_deps_on host
    platform = host['platform']
    ezbake = ezbake_config

    case platform
    when /^(fedora|el|centos)-(\d+)-(.+)$/
      variant = (($1 == 'centos')? 'el' : $1)
      version = $2
      arch = $3

      dependency_list = ezbake[:redhat][:additional_dependencies]
      dependency_list.each do |dependency|
        dependency = dependency.split
        dependency[1] = '-'
        install_package host, dependency.join('')
      end

    when /^(debian|ubuntu)-([^-]+)-(.+)$/
      variant = $1
      version = $2
      arch = $3

      dependency_list = ezbake[:debian][:additional_dependencies]
      dependency_list.each do |dependency|
        dependency = dependency.split
        package_name = dependency[0]
        package_version = dependency[2].chop # ugh
        custom_install_package_on host, package_name, package_version
      end

    else
        host.logger.notify("No repository installation step for #{platform} yet...")
    end

  end

  def install_from_ezbake(host, project_name, project_version, ezbake_dir='tmp/ezbake')
    `lein install`

    if not ezbake_config
      ezbake_stage project_name, project_version
    end

    ezbake = ezbake_config
    project_package_version = ezbake[:package_version]
    project_name = ezbake[:project]

    ezbake_staging_dir = File.join(ezbake_dir, "target/staging")

    remote_tarball = ""
    local_tarball = ""
    dir_name = ""

    Dir.chdir(ezbake_staging_dir) do
      output = `rake package:tar`

      pattern = "%s-%s"
      dir_name = pattern % [
        project_name,
        project_package_version
      ]
      local_tarball = "./pkg/" + dir_name + ".tar.gz"
      remote_tarball = "/root/" +  dir_name + ".tar.gz"

      scp_to host, local_tarball, remote_tarball
    end

    # untar tarball on host
    on host, "tar -xzf " + remote_tarball

    # install user
    group = ezbake[:group]
    user = ezbake[:user]
    manifest = <<-EOS
    group { '#{group}':
      ensure => present,
      system => true,
    }
    user { '#{user}':
      ensure => present,
      gid => '#{group}',
      managehome => false,
      system => true,
    }
    EOS
    apply_manifest_on(host, manifest)

    # "make" on target
    cd_to_package_dir = "cd /root/" + dir_name + "; "
    make_env = "env prefix=/usr confdir=/etc rundir=/var/run/#{project_name} "
    make_env += "initdir=/etc/init.d "
    on host, cd_to_package_dir + make_env + "make -e install-" + project_name

    # install init scripts and default settings, perform additional preinst
    # TODO: figure out a better way to install init scripts and defaults
    platform = host['platform']
    case platform
      when /^(fedora|el|centos)-(\d+)-(.+)$/
        make_env += "defaultsdir=/etc/sysconfig "
        on host, cd_to_package_dir + make_env + "make -e install-rpm-sysv-init"
      when /^(debian|ubuntu)-([^-]+)-(.+)$/
        make_env += "defaultsdir=/etc/defaults "
        on host, cd_to_package_dir + make_env + "make -e install-deb-sysv-init"
      else
        host.logger.notify("No ezbake installation step for #{platform} yet...")
    end
  end

  def install_jvm_puppet_on(host)
    case test_config[:jvmpuppet_install_type]
    when :package
      install_package host, 'jvm-puppet'
    when :git
      project_version = test_config[:jvmpuppet_version] ||
        `lein with-profile acceptance pprint :version | cut -d\\" -f2`
      install_from_ezbake host, 'jvm-puppet', project_version
    else
      abort("Invalid install type: " + test_config[:jvmpuppet_install_type])
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
          "http://builds.puppetlabs.lan/%s/%s/repo_configs/rpm/" % [package,
                                                                    build_version],
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
          "http://builds.puppetlabs.lan/%s/%s/repo_configs/deb/" % [package,
                                                                    build_version],
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
