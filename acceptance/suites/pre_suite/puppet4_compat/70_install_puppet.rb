require 'puppetserver/acceptance/compat_utils'

# zypper installs of puppet-agent for SLES will fail if the package can't be
# validated with the Puppet GPG key.  Beaker doesn't implicitly install the
# Puppet GPG key so need to install it before trying to install the
# puppet-agent.
step "Install Updated Puppet GPG key for Any SLES Agents."

nonmaster_agents.each { |agent|
  variant = agent['platform'].variant
  if variant == 'sles'
    on(agent, 'rpmkeys --import https://yum.puppetlabs.com/RPM-GPG-KEY-puppet')
  end
}

step "Install Legacy Puppet Agents."

default_puppet_version = '1.10.1'
puppet_version = ENV['PUPPET_LEGACY_VERSION']
if not puppet_version
  logger.info "PUPPET_LEGACY_VERSION is not set!"
  logger.info "  using default value of #{default_puppet_version}"
  puppet_version = default_puppet_version
end

install_puppet_agent_on(nonmaster_agents, {:puppet_agent_version => puppet_version})
