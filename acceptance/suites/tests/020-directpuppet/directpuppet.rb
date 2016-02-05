require 'pry'
require 'pry-debugger'


test_name 'Validate direct puppet code-id-command and code-content-command features in FOSS and PE'

# teardown 

step "SETUP: Generate a new key for the root user account to use with the git server"
  git_repo = '/git/puppetcode/environments'
  on(master, 'rm /root/.ssh/gittest_rsa', :accept_all_exit_codes => true)
  on(master, 'ssh-keygen -t rsa -V +1d -f /root/.ssh/gittest_rsa -N ""')
  gittest_key = on(master, "awk '{print $2}' /root/.ssh/gittest_rsa.pub").stdout.chomp

step 'SETUP: Install and configure git server'
  on(master, 'puppet module install puppetlabs-git --modulepath /opt/puppetlabs/puppet/modules')
  git_config=<<-GIT
    file { '/root/.ssh/config' :
      content => "Host #{master.hostname}\\nUser root\\nIdentityFile ~/.ssh/gittest_rsa\\n",
      }

    user { 'git':
      ensure => present,
      #shell => '/usr/bin/git-shell',
      home => '/home/git',
      managehome => true,
      system => true,
      }

    ssh_authorized_key { 'git_for_root' :
      type => 'ssh-rsa',
      user => 'git',
      key => '#{gittest_key}',
      }

    file { '/git' :           ensure => directory}
    file { '/git/puppetcode': ensure => directory, mode => '777'}
    
    class { 'git': }
    #git::config { 'user.name' : value => 'Tester', }
    #git::config { 'user.email': value => 'tester@puppetlabs.com', } 
    GIT

  create_remote_file(master, '/tmp/git_setup.pp', git_config)
  on master, puppet_apply('/tmp/git_setup.pp')
  on master, "sudo -u git git init --bare #{git_repo}", :pty => true
  on master, 'git init /tmp/git'
  on master, 'touch /tmp/git/test'
  #making it to here.
  on master, 'cd /tmp/git && git add /tmp/git/test'
  on master, "cd /tmp/git && git commit -m 'initial commit'"
  on master, "cd /tmp/git && git remote add origin git@$(hostname -f):#{git_repo}"

   
