test_name "Puppetserver 'ruby' subcommand tests."

# --------------------------------------------------------------------------
# This behavior needs to be baked into Beaker but is not yet. SERVER-79 tracks
# the need to fix this once Beaker has been modified to make the paths to these
# commands available.
#
cli = "puppetserver"
# --------------------------------------------------------------------------

step "Check that GEM_HOME is managed"
cmd = "GEM_HOME=UHOH #{cli} ruby -ryaml -e 'puts ENV.to_hash.to_yaml'"
on(master, cmd) do
  assert_match(/GEM_HOME/, stdout)
  assert_no_match(/UHOH/, stdout)
end

step "Check that FOO_DEBUG is filtered out"
on(master, "FOO_DEBUG=BAD #{cli} ruby -e 'puts ENV[%{FOO_DEBUG}] || %{OK}'") do
  assert_match(/^OK$/, stdout)
  assert_no_match(/BAD/, stdout, "FOO_DEBUG is not being filtered out")
end

step "Check that puppet is loadable"
cmd = "#{cli} ruby -rpuppet -e 'puts %{GOOD: } + Puppet.version'"
on(master, cmd) do
  assert_match(/GOOD:/, stdout)
  assert_no_match(/error/i, stdout)
end

step "Verify that Java cli args passed through to ruby command"
on(master, "JAVA_ARGS_CLI=-Djruby.cli.version=true #{cli} ruby -e ''") do
  assert_match(/jruby \d\.\d\.\d.*$/, stdout,
               'jruby version not included in ruby command output')
end

step "(SERVER-1759) Verify that the jruby irb exit code is used for the java return code"

on(master, "echo 'exit!(8)' | #{cli} ruby", :acceptable_exit_codes => [8])
