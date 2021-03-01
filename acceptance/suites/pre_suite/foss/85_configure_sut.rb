step "Use Java 11 on RHEL 8" do
  if master['platform'].start_with?('el-8')
    on master, 'yum install -y java-11'
    on master, 'alternatives --set java java-11-openjdk.x86_64'
    on master, 'systemctl restart puppetserver'
  end
end
