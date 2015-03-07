{
  :forge_host => 'forge-aio01-petest.puppetlabs.com',
  :is_puppetserver => true,
  :is_jvm_puppet => true,
  "service-wait" => true,
  "service-prefix" => 'service ',
  "service-num-retries" => 1500,
  "puppetservice" => 'puppetserver',
  "use-service" => true,
  "master-start-curl-retries" => 60,
  "puppetpath" => "/etc/puppetlabs/puppet",
  "puppetserver-confdir" => '/etc/puppetlabs/puppetserver/conf.d'
}

