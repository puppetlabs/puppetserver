test_name "Testing Master/Agent backwards compatibility"

step "Check that legacy agents have Puppet 4.x installed"
on(hosts, puppet("--version")) do
  assert_match(/\A4\./, stdout, "puppet --version does not start with major version 4.")
end
