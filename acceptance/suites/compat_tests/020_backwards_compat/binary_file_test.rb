require 'puppetserver/acceptance/compat_utils'

test_name 'binary file resource'

teardown do
  rm_compat_test_files()
end

agents.each do |agent|
  studio = agent.tmpdir('binary_file_test')

  step "Apply simmons::binary_file to agent #{agent.platform}" do
    apply_simmons_class(agent, studio, 'simmons::binary_file')
  end

  step "Validate binary-file" do
    sha256 = on(agent, "sha256sum #{studio}/binary-file | awk '{print $1}'").stdout.chomp
    assert_equal('55a26d3939c947e4455e8f48ffb4724170aa4f9aa4c8d58c57800ad2026f4d79', sha256)
  end

  step "Validate binary-file filebucket backup" do
    old_sha256 = on(agent, "sha256sum #{studio}/binary-file-old | awk '{print $1}'").stdout.chomp

    on(agent, puppet("filebucket restore #{studio}/binary-file-backup --digest_algorithm sha256 #{old_sha256}"))
    diff = on(agent, "diff #{studio}/binary-file-old #{studio}/binary-file-backup").exit_code
    assert_equal(0, diff, 'binary-file was not backed up to filebucket')
  end
end
