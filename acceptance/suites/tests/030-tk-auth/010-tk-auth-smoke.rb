confine :except, :platform => 'windows'

test_name "TK Auth Smoke Test Presuite" do
  
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
  step 'Acquire a git auth key ' do
    gitsshkey=File.file?("#{ENV['HOME']}/.ssh/id_rsa") ? "#{ENV['HOME']}/.ssh/id_rsa" : File.expand_path('~/.ssh/id_rsa-jenkins')
    master.do_scp_to(gitsshkey, '/root/.ssh/gitkey',options)
    on(master, 'eval $(ssh-agent)')
  end

  #TODO: skip if present
  step 'Install hocon gem' do
    #TODO: Is there a way we can calculate this location from puppet config?
    on(master, '/opt/puppetlabs/puppet/bin/gem install hocon')
  end

#TODO: Make this look in forge first, and then proceed to git if forge fails.
  step 'Install puppetlabs-hocon and puppetlabs-http_authorization modules from git' do
    environmentpath=on(master, 'puppet config print environmentpath').stdout.chomp
    environment='production'
    $manifest_path=[environmentpath,environment,'manifests','site.pp'].join('/')
    modulepath=[environmentpath,environment,'modules'].join('/')
    manifest_text = <<-END
#vcsrepo { '#{modulepath}/hocon' :
#  ensure    => present,
#  provider  => git,
#  source    => 'git@github.com:puppetlabs/puppetlabs-hocon.git',
#  user      => 'root',
#  revision  => 'master',
#  identity  => '/root/.ssh/gitkey',
#  }

vcsrepo { '#{modulepath}/http_authorization' :
  ensure    => present,
  provider  => git,
  source    => 'git@github.com:puppetlabs/puppetlabs-http_authorization',
  user      => 'root',
  revision  => 'master',
  identity  => '/root/.ssh/gitkey',
  }
  
    END
    create_remote_file(master, $manifest_path, manifest_text)
    on(master, "chmod +r #{$manifest_path}")
    on(master, 'puppet agent -t', :acceptable_exit_codes => [0,2])
  end

  #TODO: file a ticket?
  #check if version is 9.4 and fail if version isn't so we are forced to fix this 
  #when it is fixed upstream.
  step 'WORKAROUND: prerelease puppetlabs-hocon...' do
    on(master, 'perl -pi -e \'s/"version": "0.9.2"/"version": "0.9.4"/g\' /etc/puppetlabs/code/environments/production/modules/hocon/metadata.json')
  end


  step 'WORKAROUND: Disable legacy auth.conf' do
    manifest_append_text = <<-END
hocon_setting { 'jruby-puppet:use-legacy-auth-conf' :
  ensure    => present,
  path      => '/etc/puppetlabs/puppetserver/conf.d/puppetserver.conf',
  setting   => '\\"jruby-puppet.use-legacy-auth-conf\\"',
  value     => false
  }
END
    existing_manifest=on(master, 'cat $(puppet config print manifest)/site.pp').stdout
    if (!existing_manifest.include?(manifest_append_text)) then
      on(master, "echo -e \"#{manifest_append_text}\" >> #{$manifest_path}")
    end
    on(master, 'puppet agent -t', :acceptable_exit_codes => [0,2])
  end

#  #TODO: This is no longer needed, but is left here
#  to provide an example of how to write out a rule using
#  the http_authorization module.
#  These commented lines will be removed in a future PR.
#  step 'Write out a pseudo-default rule set' do
#    manifest_append_text=<<-END
#  class { 'http_authorization':
#    path => '/etc/puppetlabs/puppetserver/conf.d/auth.conf',
#  }
#  
#  http_authorization::rule { '/puppet-ca/v1/certificate_revocation_list/ca':
#    match_request_path    => '/puppet-ca/v1/certificate_revocation_list/ca',
#    match_request_type    => 'path',
#    allow                 => '*',
#    sort_order            => 100,
#    match_request_method  => 'get',
#  }
#  
#  http_authorization::rule { '/puppet-ca/v1/certificate/ca' :
#    match_request_path => '/puppet-ca/v1/certificate/ca',
#    match_request_type    => 'path',
#    match_request_method  => 'get',
#    sort_order            => 105,
#    allow_unauthenticated => true,
#    }
#  
#  http_authorization::rule { '/puppet-ca/v1/certificate/' :
#    match_request_path    => '/puppet-ca/v1/certificate/',
#    match_request_type    => 'path',
#    match_request_method  => 'get',
#    sort_order            => 110,
#    allow_unauthenticated => true,
#    }
#  
#  http_authorization::rule { '/puppet-ca/v1/certificate_request' :
#    match_request_path    => '/puppet-ca/v1/certificate_request',
#    match_request_type    => 'path',
#    sort_order            => 115,
#    allow_unauthenticated => true,
#    }
#  
#  END
#    on(master, "echo -e \"#{manifest_append_text}\" >> #{$manifest_path}")
#    on(master, 'puppet agent -t', :acceptable_exit_codes => [0,2])
#  end

  step 'restart puppetserver' do
    on(master, 'service puppetserver restart') 
  end

  step 'validate that an agent only host can perform a successful agent run' do
    on(master, 'puppet agent -t')
  end


end

