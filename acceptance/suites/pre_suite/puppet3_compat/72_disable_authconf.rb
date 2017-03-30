step "Disable tk-auth" do
  authconf = '/etc/puppetlabs/puppetserver/conf.d/auth.conf'
  create_remote_file(master, authconf, <<TKAUTH)
authorization: {
    version: 1
    rules: [
        {
          match-request: {
            path: "/"
            type: path
          }
          allow-unauthenticated: true
          sort-order: 1
          name: "allow all"
        }
    ]
}
TKAUTH
  on(master, "chmod 644 #{authconf}")
end
