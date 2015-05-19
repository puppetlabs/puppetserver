test_name "Puppetserver 'foreground' subcommand tests."

if (master['platform'] =~ /el-5/)
  skip_test("RHEL5 is not supported by this test case. See SERVER-653")
end

cli = "puppetserver"
service = "puppetserver"

# puppetserver seems to take about 45s to start up
timout_length = "60s"
foreground_cmd = "#{cli} foreground --debug"
timeout_cmd = "timeout -s INT #{timout_length} #{foreground_cmd}"

expected_messages = {
  / DEBUG .*Debug logging enabled/ => "Debug logging isn't enabled",
  /Initializing the JRuby service/ => "JRuby didn't initialize",
  /Starting web server/ => "Expected web server to start",
  /Puppet Server has successfully started and is now ready to handle requests/ => "puppetserver never finished starting",
  /Finished shutdown sequence/ => "Test ended without puppetserver shutting down gracefully"
}

# Start of test
step "Stop puppetserver"
on(master, puppet("resource service #{service} ensure=stopped"))

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
  on(master, puppet("resource service #{service} ensure=running"))
end
