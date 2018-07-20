# Test setting up Puppet Server as an Intermediate CA
# using the `puppetserver ca` subcommand.

require 'puppet_x/acceptance/pki'

test_name 'Intermediate CA setup' do

  test_agent = (agents - [master]).first
  skip_test 'requires an agent not running on the CA' unless test_agent

  def fixture(filename)
    File.read(
      File.expand_path(
        File.join('..', 'fixtures', 'chain', filename),
        __FILE__))
  end

  ssl_directory    = puppet_config(master, 'ssldir', section: 'master')
  ca_directory     = puppet_config(master, 'cadir', section: 'master')
  auth_conf        = "/etc/puppetlabs/puppetserver/conf.d/auth.conf"
  test_directory   = master.tmpdir('intermediate_ca_files')
  backup_directory = master.tmpdir('intermediate_ca_backup')
  pki              = PuppetX::Acceptance::PKI.create_chained_pki(master.to_s)
  cert_bundle_path = test_directory + '/' + 'cert_bundle.pem'
  private_key_path = test_directory + '/' + 'private_key.pem'
  crl_chain_path   = test_directory + '/' + 'crl_chain.pem'


  step 'Backup current CA infrastructure' do
    on master, "mkdir -p #{backup_directory}/{ca,ssl}"
    on master, "service #{master['puppetservice']} stop"
    on master, "cp -pR #{ca_directory}/* #{backup_directory}/ca"
    on master, "rm -rf #{ca_directory}/*"
    on master, "cp -pR #{ssl_directory}/* #{backup_directory}/ssl"
    on master, "rm -rf #{ssl_directory}/*"

    # Disable communication to services already configured to use older PKI
    on master, puppet('config set --section master route_file /tmp/nonexistent.yaml')
    if master.is_pe?
      on master, puppet('config set --section master report false')
      on master, puppet('config set --section master storeconfigs false')
      on master, puppet('config set --section master node_terminus plain')
    end
    on master, "mv #{auth_conf} #{backup_directory}/"

    create_remote_file master, auth_conf, <<-AUTHCONF
    authorization: {
      version: 1
      rules: [
        {
          match-request: {
            path: "/"
            type: path
          }
          allow-unauthenticated: true
          sort-order: 999
          name: "allow all"
        }
      ]
    }
    AUTHCONF
    on master, "chmod go+r #{auth_conf}"

    create_remote_file master, cert_bundle_path, pki[:cert_bundle]
    create_remote_file master, private_key_path, pki[:private_key]
    create_remote_file master, crl_chain_path, pki[:crl_chain]
  end

  teardown do
    on master, "service #{master['puppetservice']} stop"
    on master, "rm -rf #{ssl_directory}/*"
    on master, "cp -pR #{backup_directory}/ssl/* #{ssl_directory}/"
    on master, "rm -rf #{ca_directory}/*"
    on master, "cp -pR #{backup_directory}/ca/* #{ca_directory}/"
    on master, puppet('config set --section master route_file /etc/puppetlabs/puppet/routes.yaml')
    if master.is_pe?
      on master, puppet('config set --section master report true')
      on master, puppet("config set --section master storeconfigs true")
      on master, puppet("config set --section master node_terminus classifier")
    end
    on master, "service #{master['puppetservice']} start"
  end

  step 'Import External CA infrastructure and restart Puppet Server' do
    ca_cli = '/opt/puppetlabs/bin/puppetserver'
    on master, [ca_cli, 'ca', 'setup',
                '--private-key', private_key_path,
                '--cert-bundle', cert_bundle_path,
                '--crl-chain', crl_chain_path].join(' ')

    on master, "service #{master['puppetservice']} start"
  end


  # Remove the old CA infrastructure from an agent
  ssldir = puppet_config(test_agent, 'ssldir', section: 'agent')
  cert = puppet_config(master, 'hostcert', section: 'master')
  cacert = puppet_config(master, 'localcacert', section: 'master')
  key = puppet_config(master, 'hostprivkey', section: 'master')

  on test_agent, "rm -rf #{ssldir}"
  ssl_options = "--cert #{cert} --cacert #{cacert} --key #{key}"

  # Create a dummy agent
  on test_agent,
    puppet("agent -t --certname fake_agent_name --server #{master}"),
    :acceptable_exit_codes => [1]

  # Sign the cert
  on master, "curl -XPUT #{ssl_options} " +
             %q<-H 'Content-Type: application/json' -d '{"desired_state":"signed"}' > +
             "--url https://#{master}:8140/puppet-ca/v1/certificate_status/fake_agent_name?environment=production"
  on test_agent,
     puppet("agent -t --certname fake_agent_name --server #{master} --noop")


  # Revoke the cert -- We need to be using the master for this!
  on master, "curl -XPUT #{ssl_options} " +
             %q<-H 'Content-Type: application/json' -d '{"desired_state":"revoked"}' > +
             "--url https://#{master}:8140/puppet-ca/v1/certificate_status/fake_agent_name?environment=production"

  on master, "curl -XDELETE -H 'Content-Type: application/json' #{ssl_options} " +
             "--url https://#{master}:8140/puppet-ca/v1/certificate_status/fake_agent_name?environment=production"

  on test_agent,
     puppet("agent -t --certname fake_agent_name --server #{master}"),
     :acceptable_exit_codes => [1]

end
