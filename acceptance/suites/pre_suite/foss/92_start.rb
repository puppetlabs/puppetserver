os = on(master, "facter operatingsystem").stdout.chomp
release = on(master, "facter operatingsystemmajrelease").stdout.chomp.to_i

case os
when "RedHat", "CentOS" 
  if release > 6 then startcmd = 'systemctl start puppetserver'
  else startcmd = 'service puppetserver start'
  end
when "Ubuntu"
  if release > 14 then startcmd = 'systemctl start puppetserver'
  else startcmd = 'service puppetserver start'
  end
else
  # guess initsys
  startcmd = 'service puppetserver start'
end

on(master, startcmd, :acceptable_exit_codes => 0)

