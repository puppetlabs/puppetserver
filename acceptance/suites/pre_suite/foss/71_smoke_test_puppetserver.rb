# This step needs to execute as quickly as possible after the
# install_puppet_server master step located in 70_install_puppet.rb
#
# This is because beaker uses `puppet resource` to manage the puppetserver
# service which has the side-effect of changing ownership and permissions of key
# paths such as /var/run/puppetlabs and /etc/puppetlabs/puppet/ssl
#
# This side-effect masks legitimate issues we need to test, such as "the
# puppetserver fails to start out of the package"

step "(SERVER-414) Make sure puppetserver can start without puppet resource, "\
  "apply, or agent affecting the known good state of the SUT in a way that "\
  "causes the tests to pass with false positive successful results."


variant = master['platform'].to_array.first
case variant
  when /^(redhat|el|centos)$/
    on(master, "puppetserver ca setup")
    on(master, "service puppetserver start")
    on(master, "service puppetserver status")
    on(master, "service puppetserver stop")
  else
    step "(SERVER-414) Skipped for platform variant #{variant}"
end
