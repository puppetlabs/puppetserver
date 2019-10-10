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
 :puppet_build_version=>"c9a6daf0750f645d086ff1df54a3d65f817ff676"}
