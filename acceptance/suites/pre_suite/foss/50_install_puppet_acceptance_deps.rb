# NOTE: Ideally, the acceptance tests itself will manage its own dependencies,
# however, the puppet agent codebase is in flux with AIO changes right now so we
# are satisfying specific dependencies in the pre-suite of the puppet-server
# codebase.  If you add to this file, please comment regarding what the
# dependency is for (the specific puppet acceptance tests) so that we can easily
# move these to the ideal location in the future.

tests = options[:tests]

step "Install System Dependencies for Puppet Acceptance Tests."
hosts.each do |host|
  variant = host['platform'].to_array.first
  case variant
    when /^(debian|ubuntu)$/
      # Nothing to do
    when /^(redhat|el|centos)$/
      if tests.any? { |s| s =~ /common_package_name_in_different_providers/ }
        step "Install deps for common_package_name_in_different_providers (REMOVE ONCE https://github.com/puppetlabs/puppet/pull/3601 is merged and present in ruby/puppet/)"
        install_package host, 'createrepo'
        install_package host, 'rpm-build'
      end
  end
end

