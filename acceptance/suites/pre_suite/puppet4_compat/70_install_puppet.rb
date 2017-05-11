require 'puppetserver/acceptance/compat_utils'

step "Install Legacy Puppet Agents."

puppet_agent_version = '1.9.3'
#puppet_version = ENV['PUPPET_LEGACY_VERSION']
#if not puppet_version
#  fail "PUPPET_LEGACY_VERSION is not set, e.g. '1.9.3'"
#end

install_puppet_agent_on(nonmaster_agents, {:puppet_agent_version => puppet_agent_version})
