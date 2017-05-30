require 'puppetserver/acceptance/compat_utils'

step "Install Legacy Puppet Agents."

default_puppet_version = '3.8.7'
puppet_version = ENV['PUPPET_LEGACY_VERSION']
if not puppet_version
  logger.info "PUPPET_LEGACY_VERSION is not set!"
  logger.info "  using default value of #{default_puppet_version}"
  puppet_version = default_puppet_version
end

install_puppet_on(nonmaster_agents, {:version => puppet_version})

step "Install Puppet Server."
install_puppet_server master
