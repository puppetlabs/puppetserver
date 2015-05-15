def nonmaster_agents()
  agents.reject { |agent| agent == master }
end

def apply_simmons_class(agent, master, studio, classname)
  sitepp = '/etc/puppetlabs/code/environments/production/manifests/site.pp'
  create_remote_file(master, sitepp, <<SITEPP)
class { 'simmons':
  studio    => "#{studio}",
  exercises => [#{classname}],
}
SITEPP
  on(master, "chmod 644 #{sitepp}")
  with_puppet_running_on(master, {"master" => {"autosign" => true}}) do
    on(agent, puppet("agent --test --server #{master}"), :acceptable_exit_codes => [0,2])
  end
end

def cleanup(studio)
  on(agents, "rm -rf #{studio}")
  on(hosts, 'rm -rf $(puppet config print vardir)')
end
