{:forge_host=>"forge-aio01-petest.puppetlabs.com",
 :is_puppetserver=>true,
 :is_jvm_puppet=>true,
 "service-wait"=>true,
 "service-prefix"=>"service ",
 "service-num-retries"=>1500,
 "puppetservice"=>"puppetserver",
 "puppetserver-package"=>"puppetserver",
 "use-service"=>true,
 "master-start-curl-retries"=>60,
 "puppetserver-confdir"=>"/etc/puppetlabs/puppetserver/conf.d",
 "puppetserver-config"=>
  "/etc/puppetlabs/puppetserver/conf.d/puppetserver.conf",
 :puppet_build_version=>"53c34aeaf5d8d0963371d0ef7f4dff0c5a9962d4"}
