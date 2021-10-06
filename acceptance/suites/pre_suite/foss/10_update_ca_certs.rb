step 'Update CA certs on Centos' do
  hosts.each do |host|
    if host.platform =~ /el-6/
      # Our EL6 images do not have recent enough repos to pull down the updated ca-certificates package,
      # so we need to edit their configs before attempting to upgrade it
      on(host, 'rm -f /etc/yum.repos.d/localmirror-extras.repo /etc/yum.repos.d/localmirror-optional.repo')
      on(host, "sed -i 's/68/610/' /etc/yum.repos.d/localmirror-os.repo")
      on(host, 'yum update -y ca-certificates')
    elsif host.platform =~ /el-7/
      on(host, 'yum update -y ca-certificates')
    elsif host.platform =~ /debian|ubuntu/
      on(host, 'apt-get update')
      on(host, 'apt-get install -y ca-certificates libgnutls30')
    end
  end
end
