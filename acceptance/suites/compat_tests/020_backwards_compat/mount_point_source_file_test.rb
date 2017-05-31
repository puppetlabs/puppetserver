require 'puppetserver/acceptance/compat_utils'

test_name 'source file resource from custom mount point'

teardown do
  rm_compat_test_files()
end

agents.each do |agent|
  studio = agent.tmpdir('mount_point_source_file_test')

  step "Apply simmons::mount_point_source_file to agent #{agent.platform}" do
    apply_simmons_class(agent, studio, 'simmons::mount_point_source_file')
  end

  step "Validate mount-point-source-file" do
    contents = on(agent, "cat #{studio}/mount-point-source-file").stdout.chomp
    assert_equal('File served from custom mount point', contents)
  end
end
