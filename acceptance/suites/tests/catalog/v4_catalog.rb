require 'json'

# Here we test our v4 compile endpoint by:
#   - use "compile_service" to compile a catalog for "compile_subject"
#   - compile_subject can successfully apply the compiled catalog
#   - and if PDB is present we:
#     - facts may be found from PDB (by compiling and applying a second catalog)
#     - the catalog is successfully saved (by comparing catalog_uuids)
test_name "v4 catalog endpoint workflows" do

  no_change     = { acceptable_exit_codes: 0      }
  allow_failure = { acceptable_exit_codes: [0, 1] }
  allow_change  = { acceptable_exit_codes: [0, 2] }

  auth_path = '/etc/puppetlabs/puppetserver/conf.d/auth.conf'
  site_path = '/etc/puppetlabs/code/environments/production/manifests/site.pp'

  compile_subject = 'compile-node-one'
  compile_service = 'compile-node-two'

  catalog_path   = "/home/#{compile_subject}/catalog.json"
  request_path   = "/home/#{compile_service}/request.json"
  v4_catalog     = "https://#{master}:8140/puppet/v4/catalog"
  pdb_catalog    = "https://#{master}:8081/pdb/query/v4/catalogs/#{compile_subject}"

  path_fact_value = "/home/#{compile_subject}/test-file"
  content_fact_value = 'Fooberry Blueberry'

  old_config_string = on(master, "cat #{auth_path}").stdout
  old_config = read_tk_config_string(old_config_string)

  old_puppet_conf = puppet_conf_for(master, {})
  testdir = master.tmpdir('v4catalog')
  pdb ||= on(master,
              '[ -d /etc/puppetlabs/puppetdb ]',
              allow_failure).
              exit_code == 0

  def conf_for(non_root_agent)
    <<PUPPETCONF
[agent]
  certname = #{non_root_agent}
  server = #{master}
PUPPETCONF
  end

  def userdir_for(username)
    "/home/#{username}/.puppetlabs/"
  end

  facts = {
    "facts" => {
      "values" => {
        "test_fact_path" => path_fact_value
      }
    },
    "trusted_facts" => {
      "values" => {
        "extensions" => {
          "test_fact_content" => content_fact_value
        }
      }
    }
  }

  base_request = {
    "certname" => compile_subject,
    "persistence" => {
      "facts" => false,
      "catalog" => false
    }
  }

  persist = { "persistence" => { "facts" => true, "catalog" => true } }

  def as(user, command)
    %Q[su - #{user} -c '#{command}; exit \$?']
  end

  def curl(url:, request:false)
    cmd = %Q[curl -k \
      --cert $(puppet config print --section agent hostcert) \
      --cacert $(puppet config print --section agent localcacert) \
      --key $(puppet config print --section agent hostprivkey) \
      --tlsv1 \
      --url #{url}]
    if request
      cmd << %Q[ -d@#{request} \
      -H "Content-Type: application/json" ]
    end

    cmd
  end


  teardown do
    on(master, "puppet resource user #{compile_subject} ensure=absent managehome=true")
    on(master, "puppet resource user #{compile_service} ensure=absent managehome=true")
    on(master, "puppet resource group compile-nodes ensure=absent")
    on(master, "rm -f #{site_path}")
    modify_tk_config(master, auth_path, old_config, replace=true)

    on(master, 'echo "" > /etc/puppetlabs/puppet/puppet.conf')
    lay_down_new_puppet_conf(master, old_puppet_conf, testdir)
    reload_server
  end

  step 'allow access via tk auth' do
    config = read_tk_config_string(old_config_string)
    rules = config['authorization']['rules']
    i = rules.index {|r| r['name'] =~ /.*v4.*catalog.*services.*/ }
    new_rule = { 'match-request' => { 'path' => '^/puppet/v4/catalog/?$',
                                      'type' => 'regex',
                                      'method' => ['get', 'post']
                                    },
                 'allow' => "*",
                 'sort-order' => 10,
                 'name' => 'v4 catalog rule for testing' }
    if i
      rules[i] = new_rule
    else
      rules.unshift(new_rule)
    end

    config['authorization']['rules'] = rules

    modify_tk_config(master, auth_path, config, replace=true)
  end

  step 'allow autosigning storeconfigs' do
    new_puppet_conf = puppet_conf_for(master, {})
    new_puppet_conf['master']['autosign'] = true
    new_puppet_conf['master']['storeconfigs'] = true

    lay_down_new_puppet_conf(master, new_puppet_conf, testdir)
    reload_server
  end

  step 'create test users' do
    user_attrs = 'ensure=present managehome=true shell=/bin/bash gid=compile-nodes'
    on(master, 'puppet resource group compile-nodes ensure=present')

    [compile_subject, compile_service].each do |user|
      on(master, "puppet resource user #{user} #{user_attrs}")
      on(master, "mkdir -p #{userdir_for(user)}/etc/puppet")

      create_remote_file(master,
                         userdir_for(user) + '/etc/puppet/puppet.conf',
                         conf_for(user))

      on(master, "chown -R #{user}: #{userdir_for(user)}")

      on(master, "su -l #{user} -c '/opt/puppetlabs/bin/puppet agent -t'", allow_change)
      on(master, "su -l #{user} -c '/opt/puppetlabs/bin/puppet agent -t'", allow_change)
    end
  end

  step 'create site.pp' do
    create_remote_file(master, site_path, <<SITEPP)
# We want to make 
$foo_path = if $test_fact_path {
              "$test_fact_path"
            } else {
              "#{path_fact_value}"
            }
$foo_content = if $trusted.dig('extensions', 'test_fact_content') {
                "${trusted['extensions']['test_fact_content']}"
               } else {
                'Wrongle'
               }

node #{compile_subject} {
  file { "$foo_path":
    ensure => present,
    content => "$foo_content",
  }
}
SITEPP

    on(master, "chmod 644 #{site_path}")
  end

  step 'send initial request to v4 catalog endpoint' do
    first_request = base_request.merge(facts)
    first_request = first_request.merge!(persist) if pdb

    create_remote_file(master,
                       request_path,
                       JSON.dump(first_request))

    on(master, "chown #{compile_service}: #{request_path}")

    reply = on(master, as(compile_service, curl(request: request_path, url: v4_catalog))).stdout
    catalog = JSON.parse(reply)['catalog']

    create_remote_file(master, catalog_path, JSON.dump(catalog))

    on(master, "chown #{compile_subject}: #{catalog_path}")
  end

  step 'apply catalog from endpoint' do
    on(master,
       as(compile_subject,
          "/opt/puppetlabs/bin/puppet apply --detailed-exitcodes --catalog #{catalog_path}"),
       allow_change)

    content = on(master, "cat #{path_fact_value}").stdout.strip
    assert_equal(content, content_fact_value)
  end

  if pdb
    step 'test that facts are filled in by pdb fact storage'
      second_request = base_request.merge({'persistence' => {
                                            'catalog' => true,
                                            'facts' => false }})
      create_remote_file(master,
                         request_path,
                         JSON.dump(second_request))

      on(master, "chown #{compile_service}: #{request_path}")

      reply = on(master, as(compile_service, curl(request: request_path, url: v4_catalog))).stdout
      catalog = JSON.parse(reply)['catalog']
      catalog_uuid = catalog['catalog_uuid']

      create_remote_file(master, catalog_path, JSON.dump(catalog))

      on(master, "chown #{compile_subject}: #{catalog_path}")

      on(master,
         as(compile_subject,
            "/opt/puppetlabs/bin/puppet apply --detailed-exitcodes --catalog #{catalog_path}"),
         no_change)

      content = on(master, "cat #{path_fact_value}").stdout.strip
      assert_equal(content, content_fact_value)

    step 'test that reports are saved to PDB' do
      stored_catalog = on(master, curl(url: pdb_catalog)).stdout
      stored_catalog_uuid = JSON.parse(stored_catalog)['catalog_uuid']

      assert_equal(catalog_uuid, stored_catalog_uuid)
    end

  else
    logger.warn 'Could not find PDB, skipping PBD integration'
  end
end


