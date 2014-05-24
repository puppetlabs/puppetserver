require 'beaker/dsl/install_utils'

module JVMPuppetExtensions

  # Configuration code largely obtained from:
  # https://github.com/puppetlabs/classifier/blob/master/integration/helper.rb
  #
  def self.initialize_config(options, os_families)
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
  end

  # Return the configuration hash initialized by
  # JVMPuppetExtensions.initialize_config
  #
  def test_config
    JVMPuppetExtensions.config
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

  def get_os_family(host)
    on(host, "which yum", :silent => true)
    if result.exit_code == 0
      :redhat
    else
      :debian
    end
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

  def jvm_puppet_collect_data(host, relative_path)
    destination = File.join("./log/latest/jvm-puppet/", relative_path)
    FileUtils.mkdir_p(destination)
    scp_from master, "/var/log/jvm-puppet/jvm-puppet.log", destination
    scp_from master, "/var/log/jvm-puppet/jvm-puppet-daemon.log", destination
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

  def install_package_deps_on(host, package)
    platform = host['platform']

    case platform
      when /^(fedora|el|centos)-(\d+)-(.+)$/
        install_deps_command = "yum deplist #{package} | "
        install_deps_command += "grep provider | "
        install_deps_command += "awk '{print $2}' | "
        install_deps_command += "sort | "
        install_deps_command += "uniq | "
        install_deps_command += "grep -v #{package} | "
        install_deps_command += "sed ':a;N;$!ba;s/\\n/ /g' | "
        install_deps_command += "xargs yum -y install "
        on host, install_deps_command

      when /^(debian|ubuntu)-([^-]+)-(.+)$/
        install_deps_command = "apt-cache depends #{package} | "
        install_deps_command += "grep Depends | "
        install_deps_command += "sed 's|ends: ||' | "
        install_deps_command += "tr '\n' ' ' | "
        install_deps_command += "xargs apt-get install -y "
        on host, install_deps_command

      else
        host.logger.notify("No repository installation step for #{platform} yet...")
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
      FileUtils.rm_rf(local_path + "/target/staging")
    else
      system "git clone #{upstream_uri} #{ezbake_dir}"
    end
  end

  def install_from_ezbake(host, project_name, project_version=nil)
    tmpdir = "./tmp"
    FileUtils.mkdir_p(tmpdir)

    ezbake_dir = File.join(tmpdir, "ezbake")
    conditionally_clone "git@github.com:puppetlabs/ezbake.git", ezbake_dir

    # TODO make sure java and leiningen are installed? probably not necessary.

    # lein deploy
    `NEXUS_JENKINS_PASSWORD="#{ENV['NEXUS_JENKINS_PASSOWRD']}" NEXUS_JENKINS_USERNAME="#{ENV['NEXUS_JENKINS_USERNAME']}" lein deploy`

    if not project_version
      # assumes the project has an "acceptance" profile with lein-pprint plugin
      # installed
      project_version = `lein with-profile acceptance pprint :version | cut -d\\" -f2`
    end

    # ezbake stage
    remote_tarball = ""
    local_tarball = ""
    dir_name = ""

    Dir.chdir(ezbake_dir) do
      command = "lein run -- stage #{project_name} #{project_name}-version=#{project_version}"
      print command
      output = `#{command}`
      print output

      Dir.chdir("./target/staging") do
        match = /^Tagging git repo at (.*)/.match(output)
        project_nexus_version = match[1]

        output = `rake package:bootstrap`
        output = `rake package:tar`

        pattern = "%s-%s"
        dir_name = pattern % [
          project_name,
          project_nexus_version
        ]
        local_tarball = "./pkg/" + dir_name + ".tar.gz"
        remote_tarball = "/root/" +  dir_name + ".tar.gz"

        scp_to host, local_tarball, remote_tarball
      end
    end

    # untar tarball on host
    on host, "tar -xzf " + remote_tarball

    # install puppet user
    manifest = <<-EOS
    group { 'puppet':
      ensure => present,
      system => true,
    }
    user { 'puppet':
      ensure => present,
      gid => 'puppet',
      managehome => false,
      system => true,
    }
    EOS
    apply_manifest_on(host, manifest)

    # "make" on target
    cd_to_package_dir = "cd /root/" + dir_name + "; "
    make_env = "env prefix=/usr confdir=/etc rundir=/var/run/#{project_name} "
    on host, cd_to_package_dir + make_env + "make -e install-" + project_name

    # install init scripts and default settings
    platform = host['platform']
    case platform
      when /^(fedora|el|centos)-(\d+)-(.+)$/
        on host, "install -d -m 0755 /etc/init.d"
        on host, "install -m 0755 " + dir_name +
          "/ext/redhat/init /etc/init.d/#{project_name}"
        on host, "install -d -m 0755 /etc/sysconfig"
        on host, "install -m 0755 " + dir_name +
          "/ext/default /etc/sysconfig/#{project_name}"
      when /^(debian|ubuntu)-([^-]+)-(.+)$/
        host.logger.notify("#{platform} not yet supported.")
      else
        host.logger.notify("No ezbake installation step for #{platform} yet...")
    end

    # TODO need to ensure ownership and permissions are set correctly on all
    # files and directories, not sure how to do that on a per-platform and
    # per-project basis since each project seems to need a different user and
    # has a different set of file resources
  end

  def install_jvm_puppet_on(host)
    case test_config[:jvmpuppet_install_type]
    when :package
      custom_install_puppet_package host, 'jvm-puppet'
    when :git
      install_package_deps_on host, 'jvm-puppet'
      install_from_ezbake host, 'jvm-puppet', test_config[:jvmpuppet_version]

      # this stuff should really be done by install_project_from_ezbake but i'm
      # not sure how to do that in a generic way that is applicable to other
      # projects. might need to load some variables file from ezbake to get the
      # username and libdir values.
      on host, "find /var/lib/puppet -type d -name '*' -exec chmod 775 {} ';'"
      on host, "chown -R puppet.puppet /var/lib/puppet"
      on host, "mkdir -p /etc/puppet/manifests"
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
