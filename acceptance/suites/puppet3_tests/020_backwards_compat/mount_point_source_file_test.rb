require 'puppetserver/acceptance/compat_utils'

test_name 'source file resource from custom mount point'

agent = nonmaster_agents().first
studio = agent.tmpdir('mount_point_source_file_test')

teardown do
  cleanup(studio)
end

step "Apply simmons::mount_point_source_file to agent(s)" do
  apply_simmons_class(agent, master, studio, 'simmons::mount_point_source_file')
end

step "Validate mount-point-source-file" do
  contents = on(agent, "cat #{studio}/mount-point-source-file").stdout.chomp
  assert_equal('File served from custom mount point', contents)
end
