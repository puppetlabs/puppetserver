step "Disable auth.conf" do
  authconf = on(master, puppet("config print rest_authconfig")).stdout.chomp
  create_remote_file(master, authconf, <<AUTHCONF)
path /
auth any
allow *
AUTHCONF
  on(master, "chmod 644 #{authconf}")
end
