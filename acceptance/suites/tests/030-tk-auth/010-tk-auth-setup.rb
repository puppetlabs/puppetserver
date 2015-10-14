confine :except, :platform => 'windows'

test_name "TK Auth Smoke Test Presuite" do
# Consider combining these setup steps with the test... 
# TODO: Consider building a teardown to remove all of this setup.  Discuss with team...

  #TODO: skip if pe
  step 'Configure the server setting' do
    # so that we don't have to say puppet agent -t --server={fqdn}
    master_fqdn=on(master, 'facter fqdn').stdout.chomp
    hosts.each do |h|
      on(h, "puppet config set server #{master_fqdn}") 
    end
  end

  step 'Install required modules available on puppet forge' do
    on(master, 'puppet resource package git ensure=present')
    on(master, 'puppet module install puppetlabs-vcsrepo')
    on(master, 'puppet module install puppetlabs-stdlib')
    on(master, 'puppet module install puppetlabs-concat')
    on(master, 'puppet module install puppetlabs-hocon')
  end

  #TODO: skip if present
  step 'Install hocon gem' do
    #TODO: Is there a way we can calculate this location from puppet config?
    on(master, '/opt/puppetlabs/puppet/bin/gem install hocon')
  end


  step 'WORKAROUND: Disable legacy auth.conf' do
    manifest_append_text=(<<-ENDOFTEXT)
    hocon_setting { 'jruby-puppet:use-legacy-auth-conf' : 
      ensure    => present, 
      path      => '/etc/puppetlabs/puppetserver/conf.d/puppetserver.conf', 
      setting   => '\\"jruby-puppet.use-legacy-auth-conf\\"', 
      value     => false } 
ENDOFTEXT
    on(master, 'touch $(puppet config print manifest)/site.pp')
    existing_manifest=on(master, 'cat $(puppet config print manifest)/site.pp').stdout
    if (!existing_manifest.include?('jruby-puppet:use-legacy-auth-conf')) then
      on(master, "echo \"#{manifest_append_text}\" >> $(puppet config print manifest)/site.pp" )
    end
    on(master, 'puppet agent -t', :acceptable_exit_codes => [0,2])
  end

  step 'restart puppetserver' do
    on(master, "service #{options['puppetservice']} restart") 
  end

  step 'validate that an agent only host can perform a successful agent run' do
    on(master, 'puppet agent -t', :acceptable_exit_codes => [0,2])
  end


end

