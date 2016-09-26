test_name '027 umask is honored for files created by puppetserver service'

puppetservice=options['puppetservice']
logfile="/var/log/puppetlabs/puppetserver/puppetserver.log"

teardown do
  step 'Stop puppetserver before restoring existing log file' do
    on(master, puppet("resource service #{puppetservice} ensure=stopped"))
  end

  step 'Restore log file' do
    on(master, "if [ -f #{logfile}.bak ]; " +
        "then mv -f #{logfile}.bak #{logfile}; fi")
  end

  step 'Restart puppetserver service before ending test' do
    on(master, puppet("resource service #{puppetservice} ensure=running"))
  end
end

step 'Stop puppetserver before backing up existing log file' do
  on(master, puppet("resource service #{puppetservice} ensure=stopped"))
end

step 'Backup existing log file' do
  on(master, "if [ -f #{logfile} ]; " +
      "then mv -f #{logfile} #{logfile}.bak; fi")
end

step 'Restart puppetserver service before testing log file permissions' do
  on(master, puppet("resource service #{puppetservice} ensure=running"))
end

step 'Validate that recreated log file has 640 permissions' do
  log_permissions_mask=on(master, "stat -c '%a' #{logfile}").stdout.chomp
  assert_equal("640", log_permissions_mask,
               "#{logfile} did not have the expected permissions")
end
