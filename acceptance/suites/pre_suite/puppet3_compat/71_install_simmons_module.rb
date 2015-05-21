simmons_version = '0.3.3'

step "Install nwolfe-simmons module #{simmons_version}" do
  on(master, puppet("module install nwolfe-simmons --version #{simmons_version}"))
end

step "Configure file serving" do
  fileserverconf = on(master, puppet("config print fileserverconfig")).stdout.chomp
  create_remote_file(master, fileserverconf, <<FILESERVERCONF)
[simmons_custom_mount_point]
path /etc/puppetlabs/code/environments/production/modules/simmons/mount-point-files
allow *
FILESERVERCONF
  on(master, "chmod 644 #{fileserverconf}")
end
