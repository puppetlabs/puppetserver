require 'json'

skip_test 'SKIP: This test should only run in puppetserver FOSS.' if options[:type] == 'pe'

test_name 'SERVER-1118: Validate code-id-command feature in FOSS'

git_repo_parentdir=master.tmpdir('code_scripts_gitrepo')
git_repo="#{git_repo_parentdir}/gitdir"
git_local_repo=master.tmpdir('code_scripts_gitlocalrepo')
hostname=on(master, 'facter hostname').stdout.strip
fqdn=on(master, 'facter fqdn').stdout.strip

def cicsetting(present_or_absent='present')
  cicsetting=<<-CICS
    hocon_setting { 'code-id-command-script' :
      ensure => #{present_or_absent},
      path => '/etc/puppetlabs/puppetserver/conf.d/puppetserver.conf',
      setting => 'versioned-code.code-id-command',
      value => '/opt/puppetlabs/server/apps/puppetserver/code-id-command_script.sh',
      }
    hocon_setting { 'code-content-command-script' :
      ensure => #{present_or_absent},
      path => '/etc/puppetlabs/puppetserver/conf.d/puppetserver.conf',
      setting => 'versioned-code.code-content-command',
      value => '/opt/puppetlabs/server/apps/puppetserver/code-content-command_script.sh',
      }
    CICS
  return cicsetting
end

teardown do
  # Remove the code-id script configuration from puppetserver.conf
  remove_cicsetting=cicsetting('absent')
  create_remote_file(master, '/tmp/config_code_id_command_script_disable.pp', remove_cicsetting)
  on master, 'puppet apply /tmp/config_code_id_command_script_disable.pp'
  reload_server

  on(master, 'rm -rf /root/.ssh/gittest_rsa*', :accept_all_exit_codes => true)
  on(master, 'puppet resource user git ensure=absent')
  on(master, "rm -rf #{git_repo_parentdir}", :accept_all_exit_codes => true)
  on(master, "rm -rf #{git_local_repo}", :accept_all_exit_codes => true)
  on(master, 'rm -rf /home/git/.ssh/authorized_keys', :accept_all_exit_codes => true)
 
  #remove code_* scripts.
  on(master, 'rm -rf /opt/puppetlabs/server/apps/puppetserver/code-id-command_script.sh')
  on(master, 'rm -rf /opt/puppetlabs/server/apps/puppetserver/code_content_script.sh')

  #uninstall r10k
  on(master, '/opt/puppetlabs/puppet/bin/gem uninstall r10k')
  #return /etc/puppetlabs/code to original state
  on(master, 'rm -rf /etc/puppetlabs/code')
  on(master, 'puppet resource file /etc/puppetlabs/code ensure=directory')
end

step 'SETUP: Generate a new ssh key for the root user account to use with the git server'
  on(master, 'rm -f /root/.ssh/gittest_rsa')
  on(master, 'ssh-keygen -t rsa -V +1d -f /root/.ssh/gittest_rsa -N ""')
  gittest_key=on(master, "awk '{print $2}' /root/.ssh/gittest_rsa.pub").stdout.chomp

step 'SETUP: Install and configure git server' do
  on(master, 'puppet module install puppetlabs-git') 
  git_config=<<-GIT
    user { 'git':
      ensure => present,
      # shell => '/usr/bin/git-shell',
      home => '/home/git',
      managehome => true,
      system => true,
      }

    file { '/home/git':
      owner => 'git',
      ensure => 'directory',
      recurse => 'true',
      require => User['git'],
      }

    ssh_authorized_key { 'root@#{hostname}' :
      user => 'git',
      ensure => present,
      type => 'ssh-rsa',
      key => '#{gittest_key}',
      require => File['/home/git'],
      }

    class { 'git': }
    GIT
  create_remote_file(master, '/tmp/git_setup.pp', git_config)
  on master, puppet_apply('/tmp/git_setup.pp')
end

step 'SETUP: Write out ssh config...' do
  ssh_config=<<-SSHCONFIG
  Host #{hostname} #{fqdn}
    User git
    IdentityFile ~/.ssh/gittest_rsa
    IdentitiesOnly yes
    StrictHostKeyChecking no
  SSHCONFIG
  create_remote_file(master, '/root/.ssh/config', ssh_config)
 end

step 'SETUP: Initialize the git control repository' do
  on master, "chown git #{git_repo_parentdir}"
  on master, "sudo -u git git init --bare #{git_repo}", :pty => true
end

step 'SETUP: Initialize the local git repository' do
  on master, "chown git #{git_local_repo}"
  on master, "cd #{git_local_repo} && git config --global user.name 'TestUser'"
  on master, "cd #{git_local_repo} && git config --global user.email 'you@example.com'"
  on master, "cd #{git_local_repo} && git init"
  on master, "cd #{git_local_repo} && touch .gitignore"
  on master, "cd #{git_local_repo} && git add ."
  on master, "cd #{git_local_repo} && git commit -m 'initial commit'"
  on master, "cd #{git_local_repo} && git remote add origin git@#{fqdn}:#{git_repo}"
  on master, "cd #{git_local_repo} && git push origin master"
end

step 'SETUP: Install and configure r10k, and perform the initial commit' do
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
  on master, 'chown puppet:root /etc/puppetlabs/r10k/r10k.yaml'
  on master, "cd #{git_local_repo} && mkdir -p {modules,site/profile/manifests,hieradata}"
  on master, "cd #{git_local_repo} && touch site/profile/manifests/base.pp"
  on master, "cd #{git_local_repo} && echo 'manifest = site.pp\nmodulepath = modules:site' > environment.conf"
  on master, "cd #{git_local_repo} && echo 'hiera_include(\'classes\')' > site.pp"
  common_yaml=<<-YAML
---
classes:
- 'profile::base'

ntp::servers:
  - 0.us.pool.ntp.org
  - 1.us.pool.ntp.org
YAML
  create_remote_file(master, "#{git_local_repo}/hieradata/common.yaml", common_yaml)

  hiera_yaml=<<-YAML
---

version: 5

hierarchy:
 - name: Common
   path: common.yaml
defaults:
  data_hash: yaml_data
  datadir: hieradata
YAML
  create_remote_file(master, "#{git_local_repo}/hiera.yaml", hiera_yaml)

  puppetfile=<<-EOF
forge 'forge.puppetlabs.com'

# Forge Modules
mod 'puppetlabs/ntp', '4.1.0'
mod 'puppetlabs/stdlib'
EOF
  create_remote_file(master, "#{git_local_repo}/Puppetfile", puppetfile)
  base_pp=<<-PP
class profile::base {
  class { '::ntp': }
}
PP
  create_remote_file(master, "#{git_local_repo}/site/profile/manifests/base.pp", base_pp)
  on master, "cd #{git_local_repo} && git add ."
  on master, "cd #{git_local_repo} && git commit -m 'commit to setup r10k example'"
  on master, "cd #{git_local_repo} && git push origin production"  
  on master, "/opt/puppetlabs/puppet/bin/r10k deploy environment -p"
end

step 'SETUP: Install the code-id-command script' do
  code_id_command_script=<<-CIC
  #!/usr/bin/env sh  
  /opt/puppetlabs/puppet/bin/r10k deploy display -p --detail $1 | grep signature | grep -oE '[0-9a-f]{40}'
  CIC
  create_remote_file(master, '/opt/puppetlabs/server/apps/puppetserver/code-id-command_script.sh', code_id_command_script)
  on(master, 'chown puppet:root /opt/puppetlabs/server/apps/puppetserver/code-id-command_script.sh')
  on(master, 'chmod 770 /opt/puppetlabs/server/apps/puppetserver/code-id-command_script.sh')
end

step 'SETUP: Configure the code-id script' do
  on master, 'puppet module install puppetlabs-hocon' 
  create_remote_file(master, '/tmp/config_code_id_command_script.pp', cicsetting() )
  on master, 'puppet apply /tmp/config_code_id_command_script.pp'
  reload_server
end

step 'SETUP: Find Python executable'
  if on(master, 'which python', :acceptable_exit_codes => [0, 1]).exit_code == 1
    python_bin = 'python3'
  else
    python_bin = 'python'
  end

step 'Get the current code-id'
  current_code_id=on(master, '/opt/puppetlabs/server/apps/puppetserver/code-id-command_script.sh production').stdout.chomp

step 'Pull the catalog, validate that it contains the current code-id'
  cacert    ='/etc/puppetlabs/puppet/ssl/certs/ca.pem'
  key       ="/etc/puppetlabs/puppet/ssl/private_keys/#{fqdn}.pem"
  hostcert  ="/etc/puppetlabs/puppet/ssl/certs/#{fqdn}.pem"
  auth_str  ="--cacert #{cacert} --key #{key} --cert #{hostcert}"
  result=on(master, "curl --silent #{auth_str} https://#{fqdn}:8140/puppet/v3/catalog/#{fqdn}?environment=production | #{python_bin} -m json.tool").stdout
  catalog=JSON.parse(result)
  assert_match(current_code_id, catalog['code_id'], "FAIL: Expected catalog to contain current_code_id #{current_code_id}.")
