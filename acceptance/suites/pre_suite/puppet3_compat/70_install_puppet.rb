require 'puppetserver/acceptance/compat_utils'

step "Install Legacy Puppet Agents."

puppet_version = ENV['PUPPET_LEGACY_VERSION']
if not puppet_version
  fail "PUPPET_LEGACY_VERSION is not set, e.g. '3.7.5'"
end

install_puppet_on(nonmaster_agents, {:version => puppet_version})
