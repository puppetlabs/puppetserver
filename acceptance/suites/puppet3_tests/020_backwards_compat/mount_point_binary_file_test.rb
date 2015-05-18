require 'puppetserver/acceptance/compat_utils'

test_name 'binary file resource from custom mount point'

agent = nonmaster_agents().first
studio = agent.tmpdir('mount_point_binary_file_test')

teardown do
  cleanup(studio)
end

step "Apply simmons::mount_point_binary_file to agent(s)" do
  apply_simmons_class(agent, master, studio, 'simmons::mount_point_binary_file')
end

step "Validate mount-point-binary-file" do
  md5 = on(agent, "md5sum #{studio}/mount-point-binary-file | awk '{print $1}'").stdout.chomp
  assert_equal('4b392568e0c19886bf274663a63b7d18', md5)
end
