step "Configure Puppet Server ." 
  if master['platform'].include? 'el-6'
    on master, "sed -i.bak -e 's/localhost/#{master}/' /etc/puppet-server/conf.d/puppet-server.ini"
    on master, "sed -i.bak -e 's/localhost/#{master}/' /etc/puppet-server/conf.d/webserver.ini"
  end

