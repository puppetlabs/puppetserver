require 'puppetserver/acceptance/compat_utils'

test_name 'binary file resource'

studio = "/tmp/simmons-studio-#{Process.pid}"
agent = nonmaster_agents().first

teardown do
  cleanup(studio)
end

step "Apply simmons::binary_file to agent(s)" do
  apply_simmons_class(agent, master, studio, 'simmons::binary_file')
end

step "Validate binary-file" do
  md5 = on(agent, "openssl dgst -md5 #{studio}/binary-file | awk '{print $2}'").stdout.chomp
  assert_equal('7cfc2db80222ef224d99648716cea8e4', md5)
end

step "Validate binary-file filebucket backup" do
  old_md5 = on(agent, "openssl dgst -md5 #{studio}/binary-file-old | awk '{print $2}'").stdout.chomp
  on(agent, puppet("filebucket restore #{studio}/binary-file-backup #{old_md5} --server #{master}"))
  diff = on(agent, "diff #{studio}/binary-file-old #{studio}/binary-file-backup").exit_code
  assert_equal(0, diff, 'binary-file was not backed up to filebucket')
end
