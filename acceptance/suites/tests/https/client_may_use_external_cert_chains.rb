test_name "Ensure Puppet Server's HTTP client may use external cert chains" do

  ## This test currently fails on FIPS (see PE-34102). Remove this once that is resolved.
  skip_test if master.fips_mode?

  reports_tmpdir = master.tmpdir('external-reports')
  server_key = reports_tmpdir + '/server.key'
  server_cert = reports_tmpdir + '/server.crt'
  server_rb = reports_tmpdir + '/server.rb'
  directory_to_serve = reports_tmpdir + '/public'

  generate_self_signed_cert = "openssl req -new -newkey rsa:2048 -days 365 -nodes -x509 -keyout #{server_key} -out #{server_cert} -batch -addext 'subjectAltName = DNS:#{master}'"
  run_server = "/opt/puppetlabs/puppet/bin/ruby #{server_rb} &"
  kill_server = 'ps -ef | grep server.rb | grep -v grep | ruby -ne \'puts $_.split[1]\' | xargs -r kill' # `xargs -r` is a GNUism.
  wait_for_server = "for i in {1..40}; do if [[ 0 -ne `curl -ksw '%{exitcode}' 'https://#{master}:7777/' | tail -1` ]]; then echo 'sleeping and waiting'; sleep 2; else break; fi; done"
  check_for_webrick = %q{/opt/puppetlabs/puppet/bin/ruby -e "require 'webrick'"}

  server_script = <<EOF
    require 'webrick'
    require 'webrick/https'
    require 'openssl'
    require 'yaml'
    require 'puppet'

    cert = OpenSSL::X509::Certificate.new(File.read('#{server_cert}'))
    key = OpenSSL::PKey.read(File.read('#{server_key}'))
    log_file = File.open('#{reports_tmpdir}/webrick.log', 'a+')
    logger = WEBrick::Log.new(log_file, WEBrick::Log::DEBUG)
    access_log = [ [log_file, WEBrick::AccessLog::COMBINED_LOG_FORMAT] ]
    server = WEBrick::HTTPServer.new(Port: 7777, SSLEnable: true,
                                     SSLCertificate: cert, SSLPrivateKey: key,
                                     Logger: logger, AccessLog: access_log)
    trap(:TERM) { server.shutdown }
    server.mount_proc '/' do |request, response|
      if request.body
        report = YAML.parse(request.body).to_ruby
        File.write('#{directory_to_serve}/' + report.host, report.status)
        response.status = 200
      else
        response.status = 400
      end
    end

    WEBrick::Daemon.start

    server.start
EOF

  stores = master.is_pe? ? 'http,puppetdb' : 'http,store'
  enable_https_report_processor_config = {
    'master' => {
      'reporturl' => "https://#{master}:7777/",
      'report_include_system_store' => true,
      'reports' => stores,
      'ssl_trust_store' => server_cert
    }
  }

  teardown do
    on master, kill_server
  end


  # Test
  on master, generate_self_signed_cert
  # required so Puppet Server can read generated cert
  on master, "chmod +rx #{reports_tmpdir}"
  exit_code = on(master, check_for_webrick, acceptable_exit_codes: [0, 1]).exit_code
  if exit_code == 1
    on master, '/opt/puppetlabs/puppet/bin/gem install webrick'
  end

  create_remote_file(master, server_rb, server_script)
  on master, "mkdir #{directory_to_serve}"
  on master, run_server
  on master, wait_for_server

  with_puppet_running_on(master, enable_https_report_processor_config) do
    on master, 'puppet agent -t', acceptable_exit_codes: [0,2]
  end

  report_content = on(master, "cat #{directory_to_serve}/#{master}").stdout.chomp
  assert_match /(un)?changed/, report_content

  system_ssl_tmpdir = master.tmpdir('system_ssl_store')
  script_location = system_ssl_tmpdir + '/connection_test.rb'

  test_script = <<EOF
    require 'puppet'
    require 'puppet/server/puppet_config'
    require 'puppet/server/http_client'
    require 'uri'

    Puppet::Server::PuppetConfig.initialize_puppet(puppet_config: {})
    client = Puppet.runtime[:http]

    response = client.get(URI('https://github.com/index.html'), options: {include_system_store: true})

    puts response.code
EOF

  create_remote_file(master, script_location, test_script)
  status = on(master, "/opt/puppetlabs/server/apps/puppetserver/bin/puppetserver ruby #{script_location}").stdout.chomp
  assert_match /.*200$/, status
end
