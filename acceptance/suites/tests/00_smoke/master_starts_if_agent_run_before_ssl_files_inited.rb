test_name "Master can startup even if agent run before ssl files inited"

skip_test 'Skipping for PE since it pre-configures a CRL file and Puppet ' \
  'Server does not yet update it at startup - SERVER-346 and SERVER-911' \
  if options[:type] == 'pe'

puppetservice=options['puppetservice']
ssldir = master.puppet['ssldir']
backup_ssldir = master.tmpdir("agent_run_before_master_init_ssldir_backup")

step "Backup original SSL configuration so can be restored when test finishes" do
  on(master, "cp -pR #{ssldir} #{backup_ssldir}")
end

teardown do
  step 'Stop the server so the original SSL config can be restored' do
    # This is done as a 'service stop' as opposed to a resource 'ensure=stopped'
    # to ensure that the OS isn't trying to restart a failing service in the
    # background (e.g, on a system using systemd Restart=on-failure) and
    # potentially recreating part of the SSL state before the original state
    # can be restored.  'ensure=stopped' doesn't terminate the systemd
    # Restart=on-failure loop.
    on(master, "service #{puppetservice} stop")
  end

  # Re-enable PuppetDB facts terminus
  on(master, puppet("config set route_file /etc/puppetlabs/puppet/routes.yaml"))

  step 'Restore the original server SSL config' do
    on(master, "rm -rf #{ssldir}")
    on(master, "mv #{backup_ssldir}/#{File.basename(ssldir)} #{File.dirname(ssldir)}")
  end
  step 'Restart the server with original SSL config before ending the test' do
    on(master, puppet("resource service #{puppetservice} ensure=running"))
  end

end

step 'Disable facts reporting to PuppetDB while we munge certs' do
  on(master, puppet("config set route_file /tmp/nonexistant.yaml"))
end

step 'Ensure puppetserver has been stopped before nuking SSL directory' do
  on(master, puppet("resource service #{puppetservice} ensure=stopped"))
end

step 'Nuke the existing SSL directory' do
  on(master, "rm -rf #{ssldir}/*")
end

step 'Do an agent run with the server stopped so a public/private key can be created' do
  # The agent run is expected to return a '1' (failure) here because the server
  # it tries to contact would be down.
  on(master, puppet('agent', '--test', '--certname', master, '--server', master),
     {:acceptable_exit_codes => [1]})
end

step 'Ensure puppetserver can start successfully with the public/private key but no cert in place for master' do
  # Using 'service start' here instead of resource 'ensure=running' because
  # 'running' can return a 0 exit code whether or not the service is actually
  # started whereas 'service start' should only return a 0 exit code if the
  # startup was successful.
  on(master, 'puppetserver ca setup')
  on(master, "service #{puppetservice} start")
end

step 'Ensure an agent run with the generated master cert is now successful' do
  # Exit code of 0 (success, no changes) or 2 (success, some changes) allowed
  # since only interested in determining that the run is successful.
  on(master, puppet('agent', '--test', '--certname', master, '--server', master),
     {:acceptable_exit_codes => [0, 2]})
end
