test_name "(SERVER-1268)/(TK-293) TK-AUTH uses certificate extensions for authentication" do

confine :except, :platform => 'windows'
  
server = master.puppet['certname']
ssldir = master.puppet['ssldir']
confdir = master.puppet['confdir']

teardown do
  # restore the original tk auth.conf file
  on master, 'cp /etc/puppetlabs/puppetserver/conf.d/auth.bak /etc/puppetlabs/puppetserver/conf.d/auth.conf'

  # re-enable puppetdb facts terminus
  on master, puppet('config set route_file /etc/puppetlabs/puppet/routes.yaml')
end

step "Backup the tk auth.conf file" do
  on master, 'cp /etc/puppetlabs/puppetserver/conf.d/auth.conf /etc/puppetlabs/puppetserver/conf.d/auth.bak'
end

# Do we have a functioning cert?
step "Confirm agent can connect with existing cert" do
  agents.each do |a|
    if (not_controller(a))
      rc = on(a,
              puppet("agent --test --server #{server} --detailed-exitcodes"),
              {:acceptable_exit_codes => [0,2]})
    end
  end
end

step "Disconnect the facts terminus from PuppetDB while we're munging certs" do
  on master, puppet('config set route_file /tmp/nonexistant.yaml')
end

# Not anymore we don't
step "Revoke and destroy the existing cert on the server" do
  agents.each do |a|
    if (not_controller(a))
      rc = on(master,
              "puppetserver ca clean --certname=#{a.hostname}",
              {:acceptable_exit_codes => [0,2]})
    end
  end
end

step "Reload the server" do
  reload_server
end

# After a server HUP, the agent cert should be rejected
step "Confirm agent can't connect with existing cert" do
  agents.each do |a|
    if (not_controller(a))
      rc = on(a,
              puppet("agent --test --server #{server} --detailed-exitcodes"),
              {:acceptable_exit_codes => [1]})
    end
  end
end

step "Remove the old certs on the agents so they'll make new ones" do
  agents.each do |a|
    if (not_controller(a))
      rc = on(a,
              "find #{confdir} -name #{a.hostname}.pem -delete",
              {:acceptable_exit_codes => [0,1]})
    end
  end
end

# Lay down an attributes file for puppet to read when creating
# a new cert
# TODO: Make this a here doc with extensions that exist as vars so that they 
# can be passed into our tk auth.conf rule generator.
step "Copy the CSR attributes file into place" do
  agents.each do |a|
    if (not_controller(a))
      rc = scp_to(a,
                  'acceptance/suites/tests/authorization/fixtures/csr_attributes.yaml',
                  "#{confdir}",
                  {:acceptable_exit_codes => [0]})
    end
  end
end

step "Generate a new cert with a cert extension" do
  agents.each do |a|
    if (not_controller(a))
      rc = on(a,
              puppet("agent --test --server #{server} --detailed-exitcodes"),
              {:acceptable_exit_codes => [1]})
    end
  end
end

step "Sign the certs" do
  rc = on(master,
          'puppetserver ca sign --all',
          {:accept_all_exit_codes => true})
end

# tk_auth file that allows catalogs based on extensions rather than node names.
# This will create a weakness in that if the DEFAULT tk_auth.conf file is
# modified in the future,
# we may need to modify our test tk_auth.conf file.
# FIXME / TODO: create helper methods so that we can modify the tk auth.conf
# file in place (and therefore test more use cases.)
step "Lay down a test tk-auth.conf file" do
  scp_to( master,
    'acceptance/suites/tests/authorization/fixtures/extensions_test_auth.conf',
    '/etc/puppetlabs/puppetserver/conf.d/auth.conf',
    :acceptable_exit_codes => 0 )
end

step "Reload the server" do
  reload_server
end

# Confirm agents can connect with new cert
step "Confirm agent can connect with the new cert" do
  agents.each do |a|
    if (not_controller(a))
      rc = on(a,
              puppet("agent --test --server #{server} --detailed-exitcodes"),
              {:acceptable_exit_codes => [0,2]})
    end

    # Can we poke an HTTP API endpoint?
    cert = get_cert(a)
    key = get_key(a)
    rc = https_request("https://#{server}:8140/puppet/v3/catalog/#{a.hostname}?environment=production",
                       :get,
                       cert,
                       key)
    if (rc.code != '200')
      fail_test "Unexpected HTTP status code: #{rc.code}"
    end
  end
end

end

