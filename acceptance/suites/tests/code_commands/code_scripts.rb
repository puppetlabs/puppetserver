require 'json'
require 'scooter'
require 'pry-byebug'

test_name 'SERVER-1118/SERVER-1119: Validate code-command features'

def disable_file_sync(master)
  api = Scooter::HttpDispatchers::ConsoleDispatcher.new(dashboard)
  pe_master_group = api.get_node_group_by_name('PE Master')
  pe_master_group['classes']['puppet_enterprise::profile::master']['file_sync_enabled'] = true
  api.replace_node_group(pe_master_group['id'], pe_master_group)
  on(master, 'puppet agent -t', :acceptable_exit_codes => [0,2])
end

git_repo                    = '/git/puppetcode'
git_local_repo              = '/tmp/git'
hostname                    = on(master, 'facter hostname').stdout.strip
fqdn                        = on(master, 'facter fqdn').stdout.strip
code_id_command_path        = '/opt/puppetlabs/server/apps/puppetserver/code-id-command_script.sh'
code_content_command_path   = '/opt/puppetlabs/server/apps/puppetserver/code-content-command_script.sh'

if (options[:type] == 'pe') 
  then 
    puppet_account          = 'pe-puppet'
    puppet_server_conf      = '/etc/puppetlabs/puppetserver/conf.d/pe-puppet-server.conf'
    puppet_server_service   = 'pe-puppetserver'
    disable_file_sync(master)    
  else 
    puppet_account          = 'puppet'
    puppet_server_conf      = '/etc/puppetlabs/puppetserver/conf.d/puppetserver.conf' 
    puppet_server_service   = 'puppetserver'
  end

teardown do
  on(master, 'rm -rf /root/.ssh/gittest_rsa*', :accept_all_exit_codes => true)
  on(master, 'puppet resource user git ensure=absent')
  on(master, "rm -rf #{git_repo}", :accept_all_exit_codes => true)
  on(master, "rm -rf #{git_local_repo}", :accept_all_exit_codes => true)
  on(master, 'rm -rf /home/git/.ssh/authorized_keys', :accept_all_exit_codes => true)
  #uninstall r10k
  on(master, '/opt/puppetlabs/puppet/bin/gem uninstall r10k')
  #return /etc/puppetlabs/code to original state
  on(master, 'rm -rf /etc/puppetlabs/code')
  on(master, 'puppet resource file /etc/puppetlabs/code ensure=directory')
  #remove code_* scripts.
  on(master, "rm -rf #{code_id_command_path}")
  on(master, "rm -rf #{code_content_command_path}")
end

step 'SETUP: Enable static_catalogs'
  on(master, 'puppet config set static_catalogs true')

step 'SETUP: Generate a new ssh key for the root user account to use with the git server'
  on(master, 'ssh-keygen -t rsa -V +1d -f /root/.ssh/gittest_rsa -N ""')
  gittest_key=on(master, "awk '{print $2}' /root/.ssh/gittest_rsa.pub").stdout.chomp

step 'SETUP: Install and configure git server'
  on(master, 'puppet module install puppetlabs-git') 
  git_config=<<-GIT
    user { 'git':
      ensure => present,
      # shell => '/usr/bin/git-shell',
      home => '/home/git',
      managehome => true,
      system => true,
      }

    ssh_authorized_key { 'root@#{hostname}' :
      user => 'git',
      ensure => present,
      type => 'ssh-rsa',
      key => '#{gittest_key}',
      require => User['git'],
      }

    file { '/git':            ensure => directory, mode => '777', owner => 'git' }
    file { '/git/puppetcode': ensure => directory, mode => '777', owner => 'git' }

    class { 'git': }
    GIT
  create_remote_file(master, '/tmp/git_setup.pp', git_config)
  on master, puppet_apply('/tmp/git_setup.pp')

step 'SETUP: Write out ssh config...'
  ssh_config=<<-SSHCONFIG
  Host #{hostname} #{fqdn}
    User git
    IdentityFile ~/.ssh/gittest_rsa
    IdentitiesOnly yes
    StrictHostKeyChecking no
  SSHCONFIG
  create_remote_file(master, '/root/.ssh/config', ssh_config)

step 'SETUP: Initialize the git control repository'
  on master, "sudo -u git git init --bare #{git_repo}", :pty => true

step 'SETUP: Initialize the local git repository'
  on master, "mkdir #{git_local_repo}"
  on master, "cd #{git_local_repo} && git config --global user.name 'TestUser'"
  on master, "cd #{git_local_repo} && git config --global user.email 'you@example.com'"
  on master, "cd #{git_local_repo} && git init"
  on master, "cd #{git_local_repo} && touch .gitignore"
  on master, "cd #{git_local_repo} && git add ."
  on master, "cd #{git_local_repo} && git commit -m 'initial commit'"
  on master, "cd #{git_local_repo} && git remote add origin git@#{fqdn}:#{git_repo}"
  on master, "cd #{git_local_repo} && git push origin master"

step 'SETUP: Install and configure r10k, and perform the initial commit'
  on master, "puppet config set server #{fqdn}"
  on master, '/opt/puppetlabs/puppet/bin/gem install r10k'
  on master, "cd #{git_local_repo} && git checkout -b production"
  r10k_yaml=<<-R10K
# The location to use for storing cached Git repos
:cachedir: '/opt/puppetlabs/r10k/cache'
# A list of git repositories to create
:sources:
  # This will clone the git repository and instantiate an environment per
  # branch in /etc/puppetlabs/code/environments
  :my-org:
    remote: git@#{fqdn}:#{git_repo}
    basedir: '/etc/puppetlabs/code/environments'
R10K
  on master, 'mkdir -p /etc/puppetlabs/r10k'
  create_remote_file(master, '/etc/puppetlabs/r10k/r10k.yaml', r10k_yaml)
  on master, "chown #{puppet_account}:root /etc/puppetlabs/r10k/r10k.yaml"
  on master, "cd #{git_local_repo} && mkdir -p {modules,site/profile/manifests,hieradata}"
  on master, "cd #{git_local_repo} && touch site/profile/manifests/base.pp"
  on master, "cd #{git_local_repo} && echo 'manifest = site.pp' > environment.conf"
  site_pp=<<-EOF
file { '/tmp/testfile.txt' :
  ensure => file }
  EOF
  create_remote_file(master, "#{git_local_repo}/site.pp", site_pp)
  puppetfile=<<-EOF
forge 'forge.puppetlabs.com'
# Forge Modules
mod 'puppetlabs/ntp', '4.1.0'
mod 'puppetlabs/stdlib'
EOF
  create_remote_file(master, "#{git_local_repo}/Puppetfile", puppetfile)
  binding.pry

  on master, "cd #{git_local_repo} && git add ."
  on master, "cd #{git_local_repo} && git commit -m 'commit to setup r10k example'"
  on master, "cd #{git_local_repo} && git push origin production"  
  on master, "/opt/puppetlabs/puppet/bin/r10k deploy environment -p"

step 'SETUP: Install the code-id-command script'
  code_id_command_script=<<-CIC
  #!/usr/bin/env sh  
  /opt/puppetlabs/puppet/bin/r10k deploy display -p --detail $1 | grep signature | grep -oE '[0-9a-f]{40}'
  CIC
  create_remote_file(master, code_id_command_path, code_id_command_script)
  on(master, "chown #{puppet_account}:#{puppet_account} #{code_id_command_path}")
  on(master, "chmod 770 #{code_id_command_path}")

step 'SETUP: Install the code-content-command script'
  code_content_command_script=<<-CCC
  #!/usr/bin/env sh
  cd /tmp/git && git checkout $1 && git show $2:modules/$3
  CCC
  create_remote_file(master, code_content_command_path, code_content_command_script)
  on(master, "chown #{puppet_account}:#{puppet_account} #{code_content_command_path}")
  on(master, "chmod 770 #{code_content_command_path}")

step 'SETUP: Configure the code-id script'
  on master, 'puppet module install puppetlabs-hocon' 
  cicsetting=<<-CICS
    hocon_setting { 'code-id-command-script' :
      ensure    => present,
      path      => '#{puppet_server_conf}', 
      setting   => 'versioned-code.code-id-command',
      value     => '#{code_id_command_path}',
      }
    hocon_setting { 'code-content-command-script' :
      ensure    => present,
      path      => '#{puppet_server_conf}',
      setting   => 'versioned-code.code-content-command',
      value     => '#{code_content_command_path}',
      }
    CICS
  create_remote_file(master, '/tmp/config_code_id_command_script.pp', cicsetting)
  on master, 'puppet apply /tmp/config_code_id_command_script.pp'
  on master, "kill -HUP $(cat /var/run/puppetlabs/puppetserver/puppetserver)"

step 'Get the current code-id'
  current_code_id=on(master, "#{code_id_command_path} production").stdout.chomp

step 'Pull the catalog, validate that it contains the current code-id'
  cacert    = '/etc/puppetlabs/puppet/ssl/certs/ca.pem'
  key       = "/etc/puppetlabs/puppet/ssl/private_keys/#{fqdn}.pem"
  hostcert  = "/etc/puppetlabs/puppet/ssl/certs/#{fqdn}.pem"
  auth_str  = "--cacert #{cacert} --key #{key} --cert #{hostcert}"
  endpoint  = "/puppet/v3/catalog/"
  url       = "https://#{fqdn}:8140#{endpoint}#{fqdn}?environment=production"
  result=on(master, "curl --silent #{auth_str} --url '#{url}' | python -m json.tool")
  catalog=JSON.parse(result.stdout)
  
  binding.pry
  assert_match(current_code_id, catalog['code_id'], "FAIL: Expected catalog to contain current_code_id #{current_code_id}")
