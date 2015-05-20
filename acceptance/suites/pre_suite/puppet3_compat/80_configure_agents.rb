require 'puppetserver/acceptance/compat_utils'

step "Perform agent upgrade steps" do
  step "Enable structured facts" do
    on(agents, puppet("config set stringify_facts false --section agent"))
  end
end

# Legacy agents have $ssldir under $vardir, and tests
# need to be able to blow away $vardir after each run
step "Relocate legacy agent $ssldir" do
  on(nonmaster_agents(), puppet("config set ssldir \$confdir/ssl --section agent"))
end
