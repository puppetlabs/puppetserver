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
 :puppet_build_version=>"9d8a216e83dce955ae08069db63bb2bf9e98bb83"}
