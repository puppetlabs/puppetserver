step "Configure Puppet Server ."
  if master['platform'].include? 'el-6'
    on master, "sed -i.bak -e 's/localhost/#{master}/' /etc/puppetlabs/puppetserver/conf.d/puppetserver.conf"
    on master, "sed -i.bak -e 's/localhost/#{master}/' /etc/puppetlabs/puppetserver/conf.d/webserver.conf"
  end

