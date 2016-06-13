test_name '(SERVER-1380) CRL should reload after a server HUP' do

server = master.puppet['certname']

teardown do
  # Leave agents with working certs. Destroy the revoked cert on the master;
  # remove all traces on the agent; make the agent request a shiny new cert; 
  # have the master sign it; then use it to make sure it works.
  agents.each do |a|
    if not_controller(a)
      hostcert_file = a.puppet['hostcert']
      reqdir = a.puppet['requestdir']
      nodefqdn = a.hostname
      request_file = File.join(reqdir, nodefqdn + ".pem")

      # FIXME: this is a dirty nasty hack that Beaker should handle seamlessly
      on(master, puppet('cert', 'destroy', "#{nodefqdn}"),
         {:accept_all_exit_codes => true})
      on(a, "rm -fv #{hostcert_file} #{request_file}",
         {:accept_all_exit_codes => true})
      on(a, puppet('agent', '--test', '--server', "#{server}"),
         {:accept_all_exit_codes => true})
      on(master, puppet('cert', 'sign', "#{nodefqdn}"),
         {:accept_all_exit_codes => true})
      on(a, puppet('agent', '--test', '--server', "#{server}"),
         {:accept_all_exit_codes => true})
    end
  end
end

step 'Demonstrate that the existing certs work' do
  agents.each do |a| 
    if not_controller(a)
      rc = on(a, puppet('agent', '--test', '--server', "#{server}"),
              {:acceptable_exit_codes => [0,2]})
      fail_test('Missing expected output') unless \
        rc.stdout.include?('Applied catalog')
    end
  end
end

step 'Revoke the agent certs' do
  agents.each do |a| 
    if not_controller(a)
      rc = on(master, puppet('cert', 'revoke', "#{a.hostname}"),
              {:acceptable_exit_codes => [0,2]})
      fail_test('Missing expected output') unless \
        rc.stdout.include?('Revoked certificate')
    end
  end
end

step 'HUP the server' do
  hup_server
end

step 'Demonstrate that the certs are rejected' do
  agents.each do |a| 
    if not_controller(a)
      rc = on(a, puppet('agent', '--test', '--server', "#{server}"),
              {:acceptable_exit_codes => [1]})
      fail_test('Missing expected output') unless \
        rc.stderr.include?('SSL_connect SYSCALL returned=5')
    end
  end
end

end
