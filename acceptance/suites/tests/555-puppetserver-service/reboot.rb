

# rebooting with on(master, 'reboot') causes Beaker::..::host.connection et all to throw IOError: closed stream.
# using ruby's system call and executing an ssh command on the beaker host's shell works for now.
# when BKR-70 is resolved, method should be set to beaker, or this script should be modified
# and the system ssh method should be deleted.
#method=beaker
rebootMethod='system'

if (options[:type] == 'pe') then
  puppetserverServiceName='pe-puppetserver'
else
  puppetserverServiceName='puppetserver'
end

test_name "Validates the ability of the #{puppetserverServiceName} service to reliably start after a reboot."

max=3
sleeptime=45

# "ps auxww | grep '[s]shd:' | awk '{print $2}' | xargs kill -9" simulates 
#    the problem where sshd kills the connection too quickly for Ruby 
#    Net::SSH.  We get an IOError: stream end.
# rebootCmd="ps auxww | grep '[s]shd:' | awk '{print $2}' | xargs kill -9"
rebootCmd="reboot"

step "Ensure #{puppetserverServiceName} is enabled."
on(master, "puppet resource service #{puppetserverServiceName} enable=true")

step "\n-----------\nValidate #{puppetserverServiceName} is up and running, then reboot, repeat #{max} times\n-----------"

for i in 1..max 
  step "\n-----\nLoop #{i}\n-----"
   
  step "check uptime"
  origUptime=on(master, "awk '{print $1}' /proc/uptime").stdout.chomp.to_i

  step "reboot"
    #rebooting with on(master,'reboot') causes an IOError: stream end.  This seems like a sane workaround.
    #QENG-1914/BKR-70 for more...  
    if (rebootMethod == 'system') then 
      system "ssh -i #{options[:keyfile]} -o StrictHostKeyChecking=no root@#{master.reachable_name} \'reboot\'"
    else
      begin
        on(master, rebootCmd)
      rescue IOError => detail
        if !( detail.message == 'closed stream' ) then
          raise detail
        else
          master.connection.close
        end
      end
    end

  step "sleeping for 15 seconds and waiting for reboot."
  sleep 1
  
  master.connection.connect if (rebootMethod != 'system')

  step "look at uptime to ensure that we actually rebooted"
  secondUptime=on(master, "awk '{print $1}' /proc/uptime").stdout.chomp.to_i
  assert_operator(origUptime, :>, secondUptime, "Expect original uptime #{secondUptime} to be greater than original uptime #{origUptime} ")
  
  step "sleeping for #{sleeptime} seconds and waiting for #{puppetserverServiceName} to start."
  sleep sleeptime

  step "Check to see that #{puppetserverServiceName} is up and running."
  ec = on(master,"service #{puppetserverServiceName} status", :acceptable_exit_codes => 0..99).exit_code

  retryCounter = 0
  while (ec != 0 and retryCounter < 30) do
    sleep 1
    retryCounter += 1
    ec = on(master,"service #{puppetserverServiceName} status", :acceptable_exit_codes => 0..99).exit_code
  end

  step "Post loop check"
  on(master,"service #{puppetserverServiceName} status")

end



