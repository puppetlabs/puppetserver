require 'puppetserver/acceptance/compat_utils'

test_name 'custom fact'

teardown do
  rm_compat_test_files()
end

agents.each do |agent|
  studio = agent.tmpdir('custom_fact_output_test')

  step "Apply simmons::custom_fact_output to agent #{agent.platform}" do
    apply_simmons_class(agent, studio, 'simmons::custom_fact_output')
  end

  step "Validate custom-fact-output" do
    expected = on(agent, "hostname").stdout.chomp
    content = on(agent, "cat #{studio}/custom-fact-output").stdout.chomp
    assert_equal(expected, content)
  end
end
