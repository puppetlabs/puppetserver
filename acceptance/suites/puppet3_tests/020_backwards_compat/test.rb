test_name "3.x agent running against 4.x master"

studio = "/tmp/simmons-studio-#{Process.pid}"

teardown do
  on(agents, "rm -rf #{studio}")
end

step "Install modules" do
  simmons_version = '0.2.4'
  on(master, puppet("module install nwolfe-simmons --version #{simmons_version}"))
end

step "Configure file serving" do
  fileserverconf = on(master, puppet("config print fileserverconfig")).stdout.chomp
  create_remote_file(master, fileserverconf, <<FILESERVERCONF)
[simmons_custom_mount_point]
path /etc/puppetlabs/code/environments/production/modules/simmons/mount-point-files
allow *
FILESERVERCONF
  on(master, "chmod 644 #{fileserverconf}")
end

step "Disable auth.conf" do
  authconf = on(master, puppet("config print rest_authconfig")).stdout.chomp
  create_remote_file(master, authconf, <<AUTHCONF)
path /
auth any
allow *
AUTHCONF
  on(master, "chmod 644 #{authconf}")
end

step "Perform agent upgrade steps: enable structured facts" do
  agents.each do |agent|
    on(agent, puppet("config set stringify_facts false --section agent"))
  end
end

step "Configure site.pp" do
  sitepp = '/etc/puppetlabs/code/environments/production/manifests/site.pp'
  create_remote_file(master, sitepp, <<SITEPP)
class { 'simmons':
  studio => "#{studio}",
}
SITEPP
  on(master, "chmod 644 #{sitepp}")
end

agent = master

step "Run agents" do
  with_puppet_running_on(master, {}) do
    on(agent, puppet("agent --test --server #{master}"), :acceptable_exit_codes => [0,2])
  end
end

step "Validate content-file" do
  contents = on(agent, "cat #{studio}/content-file").stdout.chomp
  assert_equal('Static content defined in manifest', contents)
end

step "Validate source-file" do
  contents = on(agent, "cat #{studio}/source-file").stdout.chomp
  assert_equal('Static source file contents', contents)
end

step "Validate source-file filebucket backup" do
  old_md5 = on(agent, "openssl dgst -md5 #{studio}/source-file-old | awk '{print $2}'").stdout.chomp
  on(agent, puppet("filebucket restore #{studio}/source-file-backup #{old_md5} --server #{agent}"))
  diff = on(agent, "diff #{studio}/source-file-old #{studio}/source-file-backup").exit_code
  assert_equal(0, diff, 'source-file was not backed up to filebucket')
end

step "Validate binary-file" do
  md5 = on(agent, "openssl dgst -md5 #{studio}/binary-file | awk '{print $2}'").stdout.chomp
  assert_equal('7cfc2db80222ef224d99648716cea8e4', md5)
end

step "Validate binary-file filebucket backup" do
  old_md5 = on(agent, "openssl dgst -md5 #{studio}/binary-file-old | awk '{print $2}'").stdout.chomp
  on(agent, puppet("filebucket restore #{studio}/binary-file-backup #{old_md5} --server #{agent}"))
  diff = on(agent, "diff #{studio}/binary-file-old #{studio}/binary-file-backup").exit_code
  assert_equal(0, diff, 'binary-file was not backed up to filebucket')
end

step "Validate recursive-directory" do
  directory_exists = on(agent, "test -d #{studio}/recursive-directory").exit_code
  assert_equal(0, directory_exists)

  file1_contents = on(agent, "cat #{studio}/recursive-directory/file1").stdout.chomp
  assert_equal('recursive file 1', file1_contents)

  file2_contents = on(agent, "cat #{studio}/recursive-directory/file2").stdout.chomp
  assert_equal('recursive file 2', file2_contents)

  subdir_exists = on(agent, "test -d #{studio}/recursive-directory/subdir").exit_code
  assert_equal(0, subdir_exists)

  subfile_contents = on(agent, "cat #{studio}/recursive-directory/subdir/subfile").stdout.chomp
  assert_equal('recursive subfile contents', subfile_contents)
end

step "Validate mount-point-source-file" do
  contents = on(agent, "cat #{studio}/mount-point-source-file").stdout.chomp
  assert_equal('File served from custom mount point', contents)
end

step "Validate mount-point-binary-file" do
  md5 = on(agent, "openssl dgst -md5 #{studio}/mount-point-binary-file | awk '{print $2}'").stdout.chomp
  assert_equal('4b392568e0c19886bf274663a63b7d18', md5)
end

step "Validate custom-fact-output" do
  expected = on(agent, "hostname").stdout.chomp
  content = on(agent, "cat #{studio}/custom-fact-output").stdout.chomp
  assert_equal(expected, content)
end

step "Validate external-fact-output" do
  expected = on(agent, "hostname").stdout.chomp
  content = on(agent, "cat #{studio}/external-fact-output").stdout.chomp
  assert_equal(expected, content)
end
