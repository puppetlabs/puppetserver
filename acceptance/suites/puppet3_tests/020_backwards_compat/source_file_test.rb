require 'puppetserver/acceptance/compat_utils'

test_name 'source file resource'

agent = nonmaster_agents().first
studio = agent.tmpdir('source_file_test')

teardown do
  cleanup(studio)
end

step "Apply simmons::source_file to agent(s)" do
  apply_simmons_class(agent, master, studio, 'simmons::source_file')
end

step "Validate source-file" do
  contents = on(agent, "cat #{studio}/source-file").stdout.chomp
  assert_equal('Static source file contents', contents)
end

step "Validate source-file filebucket backup" do
  old_md5 = on(agent, "md5sum #{studio}/source-file-old | awk '{print $1}'").stdout.chomp
  on(agent, puppet("filebucket restore #{studio}/source-file-backup #{old_md5} --server #{master}"))
  diff = on(agent, "diff #{studio}/source-file-old #{studio}/source-file-backup").exit_code
  assert_equal(0, diff, 'source-file was not backed up to filebucket')
end
