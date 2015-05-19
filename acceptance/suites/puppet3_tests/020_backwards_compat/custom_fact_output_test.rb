require 'puppetserver/acceptance/compat_utils'

test_name 'custom fact'

agent = nonmaster_agents().first
studio = agent.tmpdir('custom_fact_output_test')

teardown do
  cleanup(studio)
end

step "Apply simmons::custom_fact_output to agent(s)" do
  apply_simmons_class(agent, master, studio, 'simmons::custom_fact_output')
end

step "Validate custom-fact-output" do
  expected = on(agent, "hostname").stdout.chomp
  content = on(agent, "cat #{studio}/custom-fact-output").stdout.chomp
  assert_equal(expected, content)
end
