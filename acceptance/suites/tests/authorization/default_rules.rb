## These test the new trapperkeeper-authorization auth.conf default rules
## as they're specified in FOSS puppet server.
## The tests are written to assert the curl request was "not forbidden" rather
## than expecting something meaningful from the endpoint. We're just trying to
## test that authorization is allowing & rejecting requests as expected.
##
## The testing pattern is to call one of the two curl functions with a path,
## and then one of the two assertion functions to validate allowed/denied.
## The assertion functions look for regexes and status codes in the stdout
## of the previous curl invocation.

test_name 'Default auth.conf rules'

step 'Turn on new auth support' do
  modify_tk_config(master, options['puppetserver-config'],
                   {'jruby-puppet' => {'use-legacy-auth-conf' => false}})
end

teardown do
  modify_tk_config(master, options['puppetserver-config'],
                   {'jruby-puppet' => {'use-legacy-auth-conf' => true}})
end

def curl_authenticated(path)
  curl = 'curl '
  curl += '--cert $(puppet config print hostcert) '
  curl += '--key $(puppet config print hostprivkey) '
  curl += '--cacert $(puppet config print localcacert) '
  curl += "--write-out '\\nSTATUSCODE=%{http_code}\\n' "
  curl += "https://#{master}:8140#{path}"
  on(master, curl)
end

def curl_unauthenticated(path)
  curl = 'curl --insecure '
  curl += "--write-out '\\nSTATUSCODE=%{http_code}\\n' "
  curl += "https://#{master}:8140#{path}"
  on(master, curl)
end

def assert_allowed(expected_statuscode = 200)
  assert_no_match(/Forbidden request/, stdout)
  assert_match(/STATUSCODE=#{expected_statuscode}/, stdout)
end

def assert_denied(expected_stdout)
  assert_match(/Forbidden request/, stdout)
  assert_match(expected_stdout, stdout)
  assert_match(/STATUSCODE=403/, stdout)
end

def report_query(node)
  curl = "/puppet/v3/report/#{node}?environment=production "
  curl += '-X PUT -H "Content-Type: text/pson" '
  curl += '--data "{\"host\":\"' + node
  curl += '\",\"metrics\":{},\"logs\":[],\"resource_statuses\":{}}"'
end

with_puppet_running_on(master, {}) do
  masterfqdn = on(master, '/opt/puppetlabs/bin/facter fqdn').stdout.chomp

  step 'environments endpoint' do
    curl_authenticated('/puppet/v3/environments')
    assert_allowed

    curl_unauthenticated('/puppet/v3/environments')
    assert_denied(/\/puppet\/v3\/environments \(method :get\)/)
  end

  step 'catalog endpoint' do
    curl_authenticated("/puppet/v3/catalog/#{masterfqdn}?environment=production")
    assert_allowed

    curl_authenticated('/puppet/v3/catalog/notme?environment=production')
    assert_denied(/\/puppet\/v3\/catalog\/notme \(method :get\)/)

    curl_unauthenticated("/puppet/v3/catalog/#{masterfqdn}?environment=production")
    assert_denied(/\/puppet\/v3\/catalog\/#{masterfqdn} \(method :get\)/)
  end

  step 'node endpoint' do
    curl_authenticated("/puppet/v3/node/#{masterfqdn}?environment=production")
    assert_allowed

    curl_authenticated('/puppet/v3/node/notme?environment=production')
    assert_denied(/\/puppet\/v3\/node\/notme \(method :get\)/)

    curl_unauthenticated("/puppet/v3/node/#{masterfqdn}?environment=production")
    assert_denied(/\/puppet\/v3\/node\/#{masterfqdn} \(method :get\)/)
  end

  step 'report endpoint' do
    curl_authenticated(report_query(masterfqdn))
    assert_allowed

    curl_authenticated(report_query('notme'))
    assert_denied(/\/puppet\/v3\/report\/notme \(method :put\)/)

    curl_unauthenticated(report_query(masterfqdn))
    assert_denied(/\/puppet\/v3\/report\/#{masterfqdn} \(method :put\)/)
  end

  step 'file_metadata endpoint' do
    # We'd actually need to install a module in order to get back a 200,
    # but we know that a 404 means we got past authorization
    curl_authenticated('/puppet/v3/file_metadata/modules/foo?environment=production')
    assert_allowed(404)

    curl_unauthenticated('/puppet/v3/file_metadata/modules/foo?environment=production')
    assert_denied(/\/puppet\/v3\/file_metadata\/modules\/foo \(method :get\)/)
  end

  step 'file_content endpoint' do
    # We'd actually need to install a module in order to get back a 200,
    # but we know that a 404 means we got past authorization
    curl_authenticated('/puppet/v3/file_content/modules/foo?environment=production')
    assert_allowed(404)

    curl_unauthenticated('/puppet/v3/file_content/modules/foo?environment=production')
    assert_denied(/\/puppet\/v3\/file_content\/modules\/foo \(method :get\)/)
  end

  step 'file_bucket_file endpoint' do
    # We'd actually need to store a file in the filebucket in order to get
    # back a 200, but we know that a 500 means we got past authorization
    curl_authenticated('/puppet/v3/file_bucket_file/md5/123?environment=production')
    assert_allowed(500)

    curl_unauthenticated('/puppet/v3/file_bucket_file/md5/123?environment=production')
    assert_denied(/\/puppet\/v3\/file_bucket_file\/md5\/123 \(method :get\)/)
  end

  step 'status endpoint' do
    curl_unauthenticated('/puppet/v3/status/foo?environment=production')
    assert_allowed
  end

  step 'status service endpoint' do
    curl_unauthenticated('/status/v1/services')
    assert_allowed
  end

  step 'static file content endpoint' do
    # We'd actually need to perform a commit and use its code-id in order to
    # get back a 200, but we know that a 400 means we got past authorization
    curl_authenticated('/puppet/v3/static_file_content/foo/bar?environment=production')
    assert_allowed(400)

    curl_unauthenticated('/puppet/v3/static_file_content/foo/bar?environment=production')
    assert_denied(/\/puppet\/v3\/static_file_content\/foo\/bar \(method :get\)/)
  end

  step 'certificate_revocation_list endpoint' do
    curl_authenticated('/puppet-ca/v1/certificate_revocation_list/ca?environment=production')
    assert_allowed

    curl_unauthenticated('/puppet-ca/v1/certificate_revocation_list/ca?environment=production')
    assert_allowed
  end

  step 'certificate endpoint' do
    curl_unauthenticated('/puppet-ca/v1/certificate/ca?environment=production')
    assert_allowed
  end

  step 'certificate_request endpoint' do
    # We'd actually need to store a CSR file on the server in order to get
    # back a 200, but we know that a 404 means we got past authorization
    curl_unauthenticated('/puppet-ca/v1/certificate_request/foo?environment=production')
    assert_allowed(404)
  end

  step 'metrics endpoint' do
    # The metrics endpoint endpoint isn't currently expected to be controlled
    # by tk-auth and so there is no rule written to the auth.conf for it explicitly.
    # This test is basically just confirming that authentication is not required to
    # in order to hit it.
    curl_unauthenticated('/metrics/v1/mbeans')
    assert_allowed
  end
end
