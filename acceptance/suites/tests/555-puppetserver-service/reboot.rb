if (options[:type] == 'pe') then
  puppetserverServiceName='pe-puppetserver'
else
  puppetserverServiceName='puppetserver'
end

test_name "Validates the ability of the #{puppetserverServiceName} service to reliably start after a reboot."

max=3
sleeptime=45
retryCounter=0
step "Ensure #{puppetserverServiceName} is enabled."
on(master, "puppet resource service #{puppetserverServiceName} enable=true")

step "\n-----------\nValidate #{puppetserverServiceName} is up and running, then reboot, repeat #{max} times\n-----------"

for i in 1..max 
  step "\n-----\nLoop #{i}\n-----"
   
  step "check uptime"
  origUptime=on(master, "awk '{print $1}' /proc/uptime").stdout.chomp.to_i

  step "reboot"
  #rebooting with on(master,'reboot') causes an IOError: stream end.  This seems like a sane workaround.
  #QENG-1914 for more...
  system "ssh -i #{options[:keyfile]} -o StrictHostKeyChecking=no root@#{master.reachable_name} \'reboot\'"

  step "sleeping for 15 seconds and waiting for reboot."
  sleep 15

  unless port_open_within?(master,22,120)
    raise Beaker::DSL::FailTest, 'Port 22 did not open on the puppet master host within 2 minutes after reboot'
  end

  step "look at uptime to ensure that we actually rebooted"
  secondUptime=on(master, "awk '{print $1}' /proc/uptime").stdout.chomp.to_i
  assert_operator(origUptime, :>, secondUptime, "Expect original uptime #{secondUptime} to be greater than original uptime #{origUptime} ")
  
  step "sleeping for #{sleeptime} seconds and waiting for #{puppetserverServiceName} to start."
  sleep sleeptime

  step "Check to see that #{puppetserverServiceName} is up and running."
  ec = on(master,"service #{puppetserverServiceName} status", :acceptable_exit_codes => 0..99).exit_code

  retryCounter = 0
  while (ec != 0 and retryCounter < 30) do
    sleep 2
    retryCounter += 1
    ec = on(master,"service #{puppetserverServiceName} status", :acceptable_exit_codes => 0..99).exit_code
  end

  step "Post loop check"
  on(master,"service #{puppetserverServiceName} status")

end



