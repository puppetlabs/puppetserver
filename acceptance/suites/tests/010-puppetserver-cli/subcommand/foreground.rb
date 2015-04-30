test_name "Puppetserver 'foreground' subcommand tests."

# --------------------------------------------------------------------------
# This behavior needs to be baked into Beaker but is not yet. SERVER-79 tracks
# the need to fix this once Beaker has been modified to make the paths to these
# commands available.
#
if master.is_pe?
  cli = "pe-puppetserver"
else
  cli = "puppetserver"
end
# --------------------------------------------------------------------------

# puppetserver seems to take about 45s to start up
timout_length = "60s"
foreground_cmd = "#{cli} foreground"
timeout_cmd = "timeout -s INT #{timout_length} #{foreground_cmd}"

expected_messages = {
  /Initializing the JRuby service/ => "JRuby didn't initialize",
  /Starting web server/ => "Expected web server to start",
  /Finished creating JRubyPuppet instance 4 of 4/ => "Expected to find 4 JRubyPuppet instances",
  /Finished shutdown sequence/ => "Test ended without puppetserver shutting down gracefully"
}

# Start of test
step "Stop puppetserver"
on(master, puppet("resource service #{cli} ensure=stopped"))

step "Run #{cli} with foreground subcommand, wait for #{timout_length}"
on(master, timeout_cmd, :acceptable_exit_codes => [124]) do |result|
  assert_no_match(/error:/i, result.stderr, "Unexpected error running puppetserver!")

  step "Check that #{cli} ran successfully and shutdown gracefully"
  expected_messages.each do |message, explanation|
    assert_match(message, result.stdout, explanation)
  end
end

teardown do
  step "Teardown: Start puppetserver again"
  on(master, puppet("resource service #{cli} ensure=running"))
end
