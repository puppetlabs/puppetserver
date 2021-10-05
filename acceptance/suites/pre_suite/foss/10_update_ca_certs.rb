step 'Update CA certs on Centos' do
  hosts.each do |host|
    if host.platform =~ /el-7/
      on(host, 'yum update -y ca-certificates')
    elsif host.platform =~ /debian|ubuntu/
      on(host, 'apt-get update')
      on(host, 'apt-get install -y ca-certificates libgnutls30')
    end
  end
end
