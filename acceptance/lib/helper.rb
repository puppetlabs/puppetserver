require 'beaker/dsl/install_utils'
require 'net/http'
require 'json'
require 'beaker-puppet'

module PuppetServerExtensions

  # Configuration code largely obtained from:
  # https://github.com/puppetlabs/classifier/blob/master/integration/helper.rb
  #
  def self.initialize_config(options)
    base_dir = File.join(File.dirname(__FILE__), '..')

    install_type = get_option_value(options[:puppetserver_install_type],
      [:git, :package], "install type", "PUPPETSERVER_INSTALL_TYPE", :package, :symbol)

    install_mode =
        get_option_value(options[:puppetserver_install_mode],
                         [:install, :upgrade], "install mode",
                         "PUPPETSERVER_INSTALL_MODE", :install, :symbol)

    puppetserver_version =
        get_option_value(options[:puppetserver_version],
                         nil, "Puppet Server Version",
                         "PUPPETSERVER_VERSION", nil, :string)

    # puppet-agent version corresponds to packaged development version located at:
    # http://builds.delivery.puppetlabs.net/puppet-agent/
    # This value now come from the beaker options file, or PUPPET_BUILD_VERSION env var
    #   the beaker options file can have this value updated via the rake task
    puppet_build_version = get_option_value(options[:puppet_build_version],
                         nil, "Puppet Agent Development Build Version",
                         "PUPPET_BUILD_VERSION",
                         nil,
                         :string)

    # puppetdb version corresponds to packaged development version located at:
    # http://builds.delivery.puppetlabs.net/puppetdb/
    puppetdb_build_version =
      get_option_value(options[:puppetdb_build_version], nil,
                       "PuppetDB Version", "PUPPETDB_BUILD_VERSION", "5.2.4", :string)

    @config = {
      :base_dir => base_dir,
      :puppetserver_install_type => install_type,
      :puppetserver_install_mode => install_mode,
      :puppetserver_version => puppetserver_version,
      :puppet_build_version => puppet_build_version,
      :puppetdb_build_version => puppetdb_build_version,
    }
  end

  def self.print_config
    pp_config = PP.pp(@config, "")
    Beaker::Log.notify "Puppet Server Acceptance Configuration:\n\n#{pp_config}\n\n"
  end

  # PuppetDB development packages aren't available on as many platforms as
  # Puppet Server's packages, so we need to restrict the PuppetDB-related
  # testing to a subset of the platforms.
  # This guards both the installation of the PuppetDB package repository file
  # and the running of the PuppetDB test(s).
  def puppetdb_supported_platforms()
    [
      /debian-7/,
      /debian-8/,
      /el/, # includes cent6,7 and redhat6,7
      /ubuntu-12/,
      /ubuntu-14/,
      /ubuntu-1604/,
    ]
  end

  class << self
    attr_reader :config
  end

  # Return the configuration hash initialized by
  # PuppetServerExtensions.initialize_config
  #
  def test_config
    PuppetServerExtensions.config
  end

  def self.get_option_value(value, legal_values, description,
    env_var_name = nil, default_value = nil, value_type = :symbol)

    # precedence is environment variable, option file, default value
    value = ((env_var_name && ENV[env_var_name]) || value || default_value)
    if value == "" and value_type == :string
      value = default_value
    elsif value and value_type == :symbol
      value = value.to_sym
    end

    unless legal_values.nil? or legal_values.include?(value)
      raise ArgumentError, "Unsupported #{description} '#{value}'"
    end

    value
  end

  def puppetserver_initialize_ssl
    hostname = on(master, 'facter hostname').stdout.strip
    fqdn = on(master, 'facter fqdn').stdout.strip

    step "Clear SSL on all hosts"
    hosts.each do |host|
      ssldir = on(host, puppet('agent --configprint ssldir')).stdout.chomp
      on(host, "rm -rf '#{ssldir}'/*")
    end

    step "Server: Start Puppet Server"
      old_retries = master['master-start-curl-retries']
      master['master-start-curl-retries'] = 300
      on master, 'puppetserver ca setup'
      with_puppet_running_on(master, "main" => { "autosign" => true, "dns_alt_names" => "puppet,#{hostname},#{fqdn}", "verbose" => true, "daemonize" => true }) do

        hosts.each do |host|
          step "Agents: Run agent --test first time to gen CSR"
          on host, puppet("agent --test --server #{master}"), :acceptable_exit_codes => [0]
        end

      end
      master['master-start-curl-retries'] = old_retries
  end

  def puppet_server_collect_data(host, relative_path)
    variant, version, _, _ = master['platform'].to_array

    # This is an ugly hack to accomodate the difficulty around getting systemd
    # to output the daemon's standard out to the same place that the init
    # scripts typically do.
    use_journalctl = false
    case variant
    when /^fedora$/
      if version.to_i >= 15
        use_journalctl = true
      end
    when /^(el|centos)$/
      if version.to_i >= 7
        use_journalctl = true
      end
    when /^debian$/
      if version.to_i >= 8
        use_journalctl = true
      end
    when /^ubuntu$/
      if version.to_i >= 15
        use_journalctl = true
      end
    when /^sles$/
      if version.to_i >= 12
        use_journalctl = true
      end
    end

    destination = File.join("./log/latest/puppetserver/", relative_path)
    FileUtils.mkdir_p(destination)
    scp_from master, "/var/log/puppetlabs/puppetserver/puppetserver.log", destination
    if use_journalctl
      puppetserver_daemon_log = on(master, "journalctl -u puppetserver", :acceptable_exit_codes => [0,1]).stdout.strip
      destination = File.join(destination, "puppetserver-daemon.log")
      File.open(destination, 'w') {|file| file.puts puppetserver_daemon_log }
    else
      scp_from master, "/var/log/puppetlabs/puppetserver/puppetserver-daemon.log", destination
    end
  end

  def install_puppet_server (host, make_env={})
    install_puppet_server_deps
    case test_config[:puppetserver_install_type]
    when :package
      install_package host, 'puppetserver'
    when :git
      project_version = 'puppetserver-version='
      project_version += test_config[:puppetserver_version] ||
        `lein with-profile ci pprint :version | tail -n 1 | cut -d\\" -f2`
      install_from_ezbake host, 'puppetserver', project_version, make_env
    else
      abort("Invalid install type: " + test_config[:puppetserver_install_type])
    end
  end

  def install_puppet_server_deps
    variant = master['platform'].variant
    version = master['platform'].version
    if variant == 'debian' && version == "8"
      create_remote_file(master, "/etc/apt/sources.list.d/jessie-backports.list", "deb http://ftp.debian.org/debian jessie-backports main")
      on master, 'apt-get update'
      master.install_package("openjdk-8-jre-headless", "-t jessie-backports")
      on master, 'update-alternatives --set java /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java'
    end
  end

  def get_defaults_file
    package_name = options['puppetserver-package']
    variant, _, _, _ = master['platform'].to_array

    case variant
      when /^(fedora|el|centos|sles)$/
        defaults_dir = "/etc/sysconfig/"
      when /^(debian|ubuntu)$/
        defaults_dir = "/etc/default/"
      else
        logger.warn("#{platform}: Unsupported platform for puppetserver.")
    end

    File.join(defaults_dir, package_name)
  end

  def get_defaults_var(host, varname)
    defaults_file = get_defaults_file

    on(host, "source #{defaults_file}; echo -n $#{varname}")
    stdout
  end

  # If we are getting the certificate for the first time, store it in the
  # beaker host options hash.  Else, return the stored certificate from the
  # beaker host options hash
  def get_cert(host)
    if host.host_hash[:cert].class == OpenSSL::X509::Certificate then
      return host.host_hash[:cert]
    else
      cert = encode_cert(host, host.puppet['hostcert'])
      host.host_hash[:cert] = cert
      return cert
    end
  end

  # Convert the contents of the certificate file in cert_file on the host
  # specified by cert_host into an X.509 certificate and return it
  # cert_host: The host whose cert you want
  # cert_file: The specific cert file you want
  # silent   : Suppress Beaker's output; set to false to see it
  def encode_cert(cert_host, cert_file, silent = true)
    rawcert = on(cert_host, "cat #{cert_file}", {:silent => silent}).stdout.strip
    OpenSSL::X509::Certificate.new(rawcert)
  end

  # Gets the key from the host hash if it is present, other wise uses
  # the encode_key method to get the key from the host, and stores it in the
  # host hash
  def get_key(host)
    if host.host_hash[:key].class == OpenSSL::PKey::RSA then
      return host.host_hash[:key]
    else
      key = encode_key(host, host.puppet['hostprivkey'])
      host.host_hash[:key] = key
      return key
    end
  end

  # Convert the contents of the private key file in key_file on the host
  # specified by key_host into an RSA private key and return it
  # key_host: The host whose key you want
  # key_file: The specific key file you want
  # silent  : Suppress Beaker's output; set to false to see it
  def encode_key(key_host, key_file, silent = true)
    rawkey = on(key_host, "cat #{key_file}", {:silent => silent}).stdout.strip
    OpenSSL::PKey::RSA.new(rawkey)
  end

  # Issue an HTTP request and return the Net::HTTPResponse object. Lifted from
  # https://github.com/puppetlabs/pe_acceptance_tests/blob/2015.3.x/lib/http_calls.rb
  # and slightly modified.
  # url: (String) URL to poke
  # method: (Symbol) :post, :get
  # cert: (OpenSSL::X509::Certificate, nil) The certificate to
  #       use for authentication.
  # key: (OpenSSL::PKey::RSA, nil) The private key to use for
  #      authentication
  # body: (String) Request body (default empty)
  require 'net/http'
  require 'uri'
  def https_request(url, request_method, cert = nil, key = nil, body = nil, options = {})
    # Make insecure https request
    uri = URI.parse(url)
    http = Net::HTTP.new(uri.host, uri.port)

    if cert
      if cert.is_a?(OpenSSL::X509::Certificate)
        http.cert = cert
      else
        raise TypeError, "cert must be an OpenSSL::X509::Certificate object, not #{cert.class}"
      end
    end

    if key
      if key.is_a?(OpenSSL::PKey::RSA)
        http.key = key
      else
        raise TypeError, "key must be an OpenSSL::PKey:RSA object, not #{key.class}"
      end
    end

    http.use_ssl = true
    http.verify_mode = OpenSSL::SSL::VERIFY_NONE
    http.read_timeout = options[:read_timeout] if options[:read_timeout]

    if request_method == :post
      request = Net::HTTP::Post.new(uri.request_uri)
      # Needs the content type even though no data is being sent
      request.content_type = 'application/json'
      request.body = body
    else
      request = Net::HTTP::Get.new(uri.request_uri)
    end

    start = Time.now
    response = http.request(request)
    stop = Time.now

    logger.debug "Remote took #{stop - start} to respond"
    response
  end

  def reload_server(host = master, opts = {})
    service = options['puppetservice']
    on(host, "service #{service} reload", opts)
  end

  def apply_one_hocon_setting(hocon_host,
                              hocon_file_path,
                              hocon_setting,
                              hocon_value)
    hocon_manifest =<<-EOF.gsub(/^ {6}/, '')
      hocon_setting { "#{hocon_setting}":
        ensure => present,
        path => "#{hocon_file_path}",
        setting => "#{hocon_setting}",
        value => #{hocon_value},
      }
    EOF
    apply_manifest_on(hocon_host, hocon_manifest,
                      {:acceptable_exit_codes => [0,2]})
  end

  def delete_one_hocon_setting(hocon_host,
                               hocon_file_path,
                               hocon_setting)
    hocon_manifest =<<-EOF.gsub(/^ {6}/, '')
      hocon_setting { "#{hocon_setting}":
        ensure => absent,
        path => "#{hocon_file_path}",
        setting => "#{hocon_setting}",
      }
    EOF
    apply_manifest_on(hocon_host, hocon_manifest,
                      {:acceptable_exit_codes => [0,2]})
  end

  # appends match-requests to TK auth.conf
  #   Provides many defaults so that users of this method can simply
  #   and easily allow a host in TK auth.conf
  #
  # NOTE: This method allows the caller to define invalid TK auth rules
  # by design.
  #
  #   TK Auth is documented here:
  #   https://github.com/puppetlabs/puppetserver/blob/master
  #   /documentation/config_file_auth.md
  #
  #   Arguments:
  #   cn:     The cannonical name, usually put in an "allow" or "deny"
  #   name:   The friendly name of the match-request
  #   host:   The system under test.  Typcially master.
  #   allow:  hostname, glob, or regex lookback to allow.
  #   allow_unauthenticated:
  #           Boolean value.  Only adds the allow-unauthenticated behavior if
  #           true.
  #   deny:   hostname, glob or regex lookback to deny
  #   sort_order:
  #   path:
  #   type:   Valid values are 'path' or 'regex'
  #   method: Should accept string or array or strings.
  #           Valid strings include 'head', 'get', 'put', 'post', 'delete'
  #
  require 'hocon/config_factory'
  def append_match_request(args)
    cn                    = args[:cn]            #The cannonical name to allow.
    name                  = args[:name] || args[:cn]  #friendly name.
    host                  = args[:host] || master
    allow                 = args[:allow]|| args[:cn]
    allow_unauthenticated = args[:allow_unauthenticated] || false
    deny                  = args[:deny] || false
    sort_order            = args[:sort_order] || 77
    path                  = args[:path] || '/'
    type                  = args[:type] || 'path'
    default_http_methods  = ['head', 'get', 'put', 'post', 'delete']
    method                = args[:method] || default_http_methods
    query_params          = args[:query_params] || {}
    #TODO: handle TK-293 X509 extensions.
    authconf_file         = args[:authconf_file] ||
	options[:'puppetserver-confdir']+'/auth.conf'

    match_request = { 'match-request' =>
       {  'path'        => path,
          'type'        => type,
          'method'      => method,
       },
       'sort-order'  => sort_order,
       'name'        => name
       }

    #Note: If you set 'allow', 'allow-unauthenticated', and 'deny' you will
    #have an invalid match-request.
    match_request.merge!('allow' => allow) if allow
    match_request.merge!('allow-unauthenticated' => true) if allow_unauthenticated
    match_request.merge!('deny' => deny) if deny

    authconf_text = on(master, "cat #{authconf_file}").stdout
    authconf_hash = Hocon.parse(authconf_text)
    authconf_hash['authorization']['rules'] << match_request

    modify_tk_config(host, authconf_file, authconf_hash, true)
  end

  # parses text into json if possible
  def parse_json(text)
    begin
      json = JSON.parse(text)
    rescue JSON::ParserError => e
      fail_test "Response body is not parseable JSON\n" +
                    "Exception message: #{e.message}\n" +
                    "Text to parse: #{text}\n"
    end
    return json
  end

  ###############################################################################
  # Metrics Helpers
  ###############################################################################
  # returns the list of standard metrics fields
  def metrics_fields
    %w( count max min mean stddev p50 p75 p95 )
  end

  def standard_metrics
    %w(
      compiler
      compiler.find_facts
      compiler.find_node
      functions
      http.active-histo
      http.puppet-v3-catalog-.*.-requests
      http.puppet-v3-environment-.*.-requests
      http.puppet-v3-environment_classes-.*.-requests
      http.puppet-v3-environments-requests
      http.puppet-v3-file_bucket_file-.*.-requests
      http.puppet-v3-file_content-.*.-requests
      http.puppet-v3-file_metadata-.*.-requests
      http.puppet-v3-file_metadatas-.*.-requests
      http.puppet-v3-node-.*.-requests
      http.puppet-v3-report-.*.-requests
      http.puppet-v3-static_file_content-.*.-requests
      http.total-requests
      jruby.borrow-timer
      jruby.free-jrubies-histo
      jruby.lock-held-timer
      jruby.lock-wait-timer
      jruby.requested-jrubies-histo
      jruby.wait-timer
    )
  end

  def single_field_metrics
    %w(
      http.active-requests.count
      http.puppet-v3-catalog-.*.-percentage
      http.puppet-v3-environment-.*.-percentage
      http.puppet-v3-environment_classes-.*.-percentage
      http.puppet-v3-environments-percentage
      http.puppet-v3-file_bucket_file-.*.-percentage
      http.puppet-v3-file_content-.*.-percentage
      http.puppet-v3-file_metadata-.*.-percentage
      http.puppet-v3-file_metadatas-.*.-percentage
      http.puppet-v3-node-.*.-percentage
      http.puppet-v3-report-.*.-percentage
      http.puppet-v3-static_file_content-.*.-percentage
      http.puppet-v3-status-.*.-percentage
      num-cpus
      uptime
      jruby.borrow-count.count
      jruby.borrow-retry-count.count
      jruby.borrow-timeout-count.count
      jruby.request-count.count
      jruby.return-count.count
      jruby.num-free-jrubies
      jruby.num-jrubies
    )
  end

  # returns an array of default metrics by building the standard field based
  # metrics, then appending the single field metrics
  def build_default_metrics
    default_metrics = Array.new
    standard_metrics.each do |sm|
      metrics_fields.each do |f|
        default_metrics << "#{sm}.#{f}"
      end
    end
    default_metrics = default_metrics + single_field_metrics
    return default_metrics
  end

  # Strips the nils recorded by the graphite system that occur when the
  # graphite system is recording on a faster cadence than the puppetserver
  # system
  def strip_nils(json)
    json.each do |json_member|
      json_member['datapoints'].each_index do |i|
        while ( json_member['datapoints'][i] != nil &&
            json_member['datapoints'][i][0] == nil ) do
          json_member['datapoints'].delete_at(i)
        end
      end
    end
  end

  def metrics_target(master_hostname, metric_name)
    return_string = "puppetlabs.#{master_hostname}.#{metric_name}"
  end

  def build_metrics_url(master, graphite_host, metrics, minutes)
    url = "http://#{graphite_host}/render?"
    url += "from=-#{minutes}minutes"
    if metrics.is_a?(String)
      url += '&target=' << metrics_target(master, metrics)
    elsif metrics.is_a?(Array)
      metrics.each do |m|
        url += '&target=' << metrics_target(master, m)
      end
    end
    url += '&format=json'
  end

  # returns a hash of metrics results
  def query_metrics(master_hostname, graphite_hostname, metrics, minutes=10)
    sleep_between_queries = 5
    seconds_remaining = minutes * 60
    full_results = Hash.new
    metrics.each do |m|
      url       = build_metrics_url(master_hostname, graphite_hostname, m, minutes)
      uri       = URI.parse(url)
      cont = true
      loop do
        response = Net::HTTP.get_response(uri)
        assert_equal("200", response.code, 'Expected response code 200')
        json = parse_json(response.body)
        loop_results = strip_nils(json)
        case
          when (loop_results.size != 0 &&
              loop_results[0]['datapoints'].size != 0)
            full_results["#{master_hostname}.#{m}"] =
                loop_results[0]['datapoints']
            cont = false
          when seconds_remaining > 0
            logger.info "#{m} metric not found in graphite, waiting " \
              "#{sleep_between_queries} seconds before trying to get it again"
            sleep sleep_between_queries
            seconds_remaining -= sleep_between_queries
          else
            cont = false
        end
        break if !cont
      end
    end
    return full_results
  end

  # builds and returns an array of missing metrics names.
  # expected metrics is an array of metric names, typically generated with
  # the build_default_metrics method
  # actual_metrics_results is an array of arrays, typically generated with the
  # query_metrics method.
  def build_missing_metrics_list(master_hostname, expected_metrics, actual_metrics_results)
    missing_metrics = Array.new
    expected_metrics.each do |m|
      if ( !actual_metrics_results.include?("#{master_hostname}.#{m}") ||
          !actual_metrics_results["#{master_hostname}.#{m}"].class == Array ||
          actual_metrics_results["#{master_hostname}.#{m}"].empty? )
        missing_metrics << "#{master_hostname}.#{m}"
      end
    end
    return missing_metrics
  end
end

Beaker::TestCase.send(:include, PuppetServerExtensions)
