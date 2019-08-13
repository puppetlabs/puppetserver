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
 :puppet_build_version=>"b89d9a3d130780ce00ea83346d4c22810d10b1cb"}
