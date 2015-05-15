require 'puppetserver/acceptance/compat_utils'

test_name 'binary file resource from custom mount point'

studio = "/tmp/simmons-studio-#{Process.pid}"
agent = nonmaster_agents().first

teardown do
  cleanup(studio)
end

step "Apply simmons::mount_point_binary_file to agent(s)" do
  apply_simmons_class(agent, master, studio, 'simmons::mount_point_binary_file')
end

step "Validate mount-point-binary-file" do
  md5 = on(agent, "openssl dgst -md5 #{studio}/mount-point-binary-file | awk '{print $2}'").stdout.chomp
  assert_equal('4b392568e0c19886bf274663a63b7d18', md5)
end
