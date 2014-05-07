step "Configure JVM Puppet Master." 
  if master['platform'].include? 'el-6'
    on master, "sed -i.bak -e 's/localhost/#{master}/' /etc/jvm-puppet/conf.d/jvm-puppet.ini"
    on master, "sed -i.bak -e 's/localhost/#{master}/' /etc/jvm-puppet/conf.d/webserver.ini"
  end

