
#-------------------------------------------------------------------------------
# Copied from
# https://github.com/puppetlabs/puppet/tree/9610a4/acceptance/lib/puppet/acceptance
#

def initialize_ssl
  hostname = on(master, 'facter hostname').stdout.strip
  fqdn = on(master, 'facter fqdn').stdout.strip

  step "Clear SSL on all hosts"
  hosts.each do |host|
    ssldir = on(host, puppet('agent --configprint ssldir')).stdout.chomp
    on(host, "rm -rf '#{ssldir}'")
  end

  step "Master: Start Puppet Master"
    with_puppet_running_on(master, "main" => { "dns_alt_names" => "puppet,#{hostname},#{fqdn}", "verbose" => true, "daemonize" => true }) do

      hosts.each do |host|
        next if host['roles'].include? 'master'

        step "Agents: Run agent --test first time to gen CSR"
        on host, puppet("agent --test --server #{master}"), :acceptable_exit_codes => [1,0]
      end

    end
end
