require 'puppetserver/acceptance/compat_utils'

test_name 'executable external fact'

skip_test 'Executable external facts broken until PUP-4420'

studio = "/tmp/simmons-studio-#{Process.pid}"
agent = nonmaster_agents().first

teardown do
  cleanup(studio)
end

step "Apply simmons::external_fact_output to agent(s)" do
  apply_simmons_class(agent, master, studio, 'simmons::external_fact_output')
end

step "Validate external-fact-output" do
  expected = on(agent, "hostname").stdout.chomp
  content = on(agent, "cat #{studio}/external-fact-output").stdout.chomp
  assert_equal(expected, content)
end
