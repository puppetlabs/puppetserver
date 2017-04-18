test_name "(TK-293) TK-AUTH uses certificate extensions for authentication" do

server = master.puppet['certname']
ssldir = master.puppet['ssldir']
confdir = master.puppet['confdir']

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

# Not anymore we don't
step "Revoke and destroy the existing cert on the server" do
  agents.each do |a| 
    if (not_controller(a))
      nodename = fact_on(a, 'fqdn')
      rc = on(master, 
              puppet("cert destroy #{nodename}"),
              {:acceptable_exit_codes => [0,2]})
    end
  end
end

# FIXME: Should actually HUP the server instead of stopping/restarting
step "HUP the server" do
  rc = on(master,
          "pgrep -f puppetserver",
          {:acceptable_exit_codes => [0]})
  pid = rc.stdout
  rc = on(master,
          "kill -HUP #{pid}",
          {:acceptable_exit_codes => [0]})
  sleep 5
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
      nodename = fact_on(a, 'fqdn')
      rc = on(a,
              "find #{confdir} -name #{nodename}.pem -delete",
              {:acceptable_exit_codes => [0,1]})
    end
  end
end

# Lay down an attributes file for puppet to read when creating
# a new cert
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

# # FIXME
# step "Update tkauth config file to use CSR attribute to control access" do
#   agents.each do |a|
#     if (not_controller(a))
#       cn = fact_on(a, 'fqdn')
#       path = '/puppet/v3/catalogs/#{fqdn}?environment=production'
#       method = 'git'
#       append_match_request()
#     end
#   end
# end

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
          puppet("cert sign --all"),
          {:accept_all_exit_codes => true})
end

# FIXME: Should actually HUP the server instead of stopping/restarting
step "HUP the server" do
  rc = on(master,
          "pgrep -f puppetserver",
          {:acceptable_exit_codes => [0]})
  pid = rc.stdout
  rc = on(master,
          "kill -HUP #{pid}",
          {:acceptable_exit_codes => [0]})
  sleep 5
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
    nodename = fact_on(a, 'fqdn')
    cert = encode_cert(a, a.puppet['hostcert'])
    key = encode_key(a, a.puppet['hostprivkey'])
    rc = https_request("https://#{server}:8140/puppet/v3/catalog/#{nodename}?environment=production",
                       :get,
                       cert,
                       key)
    if (rc.code != '200')
      fail_test "Unexpected HTTP status code: #{rc.code}"
    end
  end
end

end

