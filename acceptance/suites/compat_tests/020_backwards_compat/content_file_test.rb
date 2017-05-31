require 'puppetserver/acceptance/compat_utils'

test_name 'content file resource'

teardown do
  rm_compat_test_files()
end

agents.each do |agent|
  studio = agent.tmpdir('content_file_test')

  step "Apply simmons::content_file to agent #{agent.platform}" do
    apply_simmons_class(agent, studio, 'simmons::content_file')
  end

  step "Validate content-file" do
    contents = on(agent, "cat #{studio}/content-file").stdout.chomp
    assert_equal('Static content defined in manifest', contents)
  end
end
