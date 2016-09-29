test_name 'Service returns error if restarted and puppetserver errors out'

puppetservice=options['puppetservice']
bogus_config_file='/etc/puppetlabs/puppetserver/conf.d/bogus.conf'

teardown do
  step 'Remove bogus config file' do
    on(master, "rm -f #{bogus_config_file}")
  end

  step 'Restart puppet server for next test' do
    bounce_service(master, puppetservice)
  end
end

step 'Create config file with bogus data' do
  on(master, "echo '12345' > #{bogus_config_file}")
end

step 'Ensure puppetserver fails to start' do
  on(master, "service #{puppetservice} stop")
  on(master, "service #{puppetservice} start", {:acceptable_exit_codes => [1]})
end

step 'Ensure puppetserver fails to start after a restart' do
  # Sleep a few seconds to try to provoke contention between the automatic
  # restart that systemd would do - for restart=on-failure - and the
  # 'manually requested' one below.  The 'manually requested' restart should
  # complete but with an error (1) status code.
  sleep 5
  on(master, "service #{puppetservice} restart",
     {:acceptable_exit_codes => [1]})
end
