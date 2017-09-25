matching_puppetdb_platform = puppetdb_supported_platforms.select { |r| r =~ master.platform }
skip_test unless matching_puppetdb_platform.length > 0

install_opts = options.merge( { :dev_builds_repos => ["puppet"] })
repo_config_dir = 'tmp/repo_configs'

step "Install PuppetDB repository" do
  install_puppetlabs_dev_repo(
    master, 'puppetdb', test_config[:puppetdb_build_version],
    repo_config_dir, install_opts)

  # Internal packages on ubuntu/debian aren't authenticated and thus apt
  # will fail to install PuppetDB on those platforms.
  # This hack tells apt that we can trust the PuppetDB packages until RE-6014
  # is resolved, at which point this [trusted=yes] will already be in the file
  # and we can delete the on(master, "sed ...") block below.
  # This should make the puppetlabs-puppetdb module happily install PuppetDB on
  # ubuntu/debian.
  on(master, <<TRUSTPACKAGES)
if [ -e /etc/apt/sources.list.d/pl-puppetdb* ]; then
sed -i -e 's/deb/deb [trusted=yes]/1' /etc/apt/sources.list.d/pl-puppetdb*
fi
TRUSTPACKAGES
end 

