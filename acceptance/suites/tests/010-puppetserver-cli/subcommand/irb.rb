test_name "Puppetserver 'irb' subcommand tests."

# --------------------------------------------------------------------------
# This behavior needs to be baked into Beaker but is not yet. SERVER-79 tracks
# the need to fix this once Beaker has been modified to make the paths to these
# commands available.
#
cli = "puppetserver"
# --------------------------------------------------------------------------

step "Check that GEM_HOME is managed"
cmd = "echo 'puts ENV.to_hash.to_yaml' | GEM_HOME=UHOH #{cli} irb -ryaml -f"
on(master, cmd) do |result|
  assert_match(/GEM_HOME/, result.stdout)
  refute_match(/UHOH/, result.stdout)
end

step "Check that FOO_DEBUG is filtered"
cmd = "echo 'puts ENV[%{FOO_DEBUG}] || %{OK}' | FOO_DEBUG=BAD #{cli} irb -f"
on(master, cmd) do |result|
  assert_match(/^OK$/, result.stdout)
  refute_match(/^BAD$/, result.stdout, "FOO_DEBUG is not being filtered out")
end

step "Check that puppet is loadable"
cmd = "echo 'puts %{GOOD: } + Puppet.version' | #{cli} irb -rpuppet -f"
on(master, cmd) do |result|
  assert_match(/GOOD:/, result.stdout)
  refute_match(/error/i, result.stdout)
end

step "Verify that Java cli args passed through to irb command"
on(master, "echo '' | JAVA_ARGS_CLI=-Djruby.cli.version=true #{cli} irb -f") do |result|
  assert_match(/jruby \d\.\d\.\d.*$/, result.stdout,
               'jruby version not included in irb command output')
end

step "(SERVER-1759) Verify that the jruby irb exit code is used for the java return code"

on(master, "echo 'exit!(4)' | #{cli} irb", :acceptable_exit_codes => [4])
