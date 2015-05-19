require 'puppetserver/acceptance/compat_utils'

test_name 'executable external fact'

agent = nonmaster_agents().first

# This skip should be removed when executable external facts work for Puppet 4.x
# on Linux-based platforms again (PUP-4420)
skip_test unless agent['platform'] =~ /windows/

studio = agent.tmpdir('external_fact_output_test')

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
