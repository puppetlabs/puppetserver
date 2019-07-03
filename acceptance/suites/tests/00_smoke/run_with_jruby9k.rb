test_name "Service and apps run with JRuby 9k configured"

skip_test 'SKIP: This test should only run in puppetserver FOSS for now' if options[:type] == 'pe'

puppetservice = options['puppetservice']
defaults_file = get_defaults_file
cli = "puppetserver"

backup_dir = master.tmpdir("run_with_jruby9k")
environments_dir = "/etc/puppetlabs/code/environments"
production_dir = "#{environments_dir}/production"
manifests_dir = "#{production_dir}/manifests"
module_dir = "#{production_dir}/modules/rubyvers"
function_dir = "#{module_dir}/lib/puppet/functions"
function_rb = "#{function_dir}/rubyvers.rb"
sitepp = "#{manifests_dir}/site.pp"

step "Backup original files so can be restored when test finishes" do
  on(master, "cp -fp #{get_defaults_file} #{backup_dir}/defaults")
  on(master, "if [ -f #{sitepp} ]; " +
      "then mv #{sitepp} #{backup_dir}; fi")
end

teardown do
  step 'Restore original files' do
    on(master, "if [ -f #{backup_dir}/site.pp ]; " +
        "then mv #{backup_dir}/site.pp #{sitepp}; " +
        "else rm -f #{sitepp}; fi")
    on(master, "mv #{backup_dir}/defaults #{defaults_file}")
    on(master, "rm -fR #{module_dir}")
  end

  step 'Restart the server with the original config before ending the test' do
    on(master, "service #{puppetservice} restart")
  end
end

step 'Configure the JRuby 9k jar to be used' do
  on(master, "echo 'JRUBY_JAR=${INSTALL_DIR}/jruby-9k.jar' >> #{defaults_file}")
end

step 'Configure manifest and function for getting Ruby language version from master' do
  on master, "mkdir -p #{manifests_dir}"
  create_remote_file(master, sitepp, <<SITEPP)
notify { 'rubyvers':
  message => rubyvers()
}
SITEPP

  on master, "mkdir -p #{function_dir}"
  create_remote_file(master, function_rb, <<FUNCTIONRB)
Puppet::Functions.create_function(:'rubyvers') do
  def rubyvers()
    RUBY_VERSION
  end
end
FUNCTIONRB

  if options[:type] == 'pe'
    runuser = 'pe-puppet'
  else
    runuser = 'puppet'
  end

  on(master, "chown -R #{runuser} #{environments_dir}")
end

step 'Restart master with JRuby 9k' do
  on(master, "service #{puppetservice} restart")
end

step 'Ensure that the server is running Ruby language version 2+' do
   on(master, puppet('agent', '--test'),
      {:acceptable_exit_codes => [0, 2]}) do
     assert_match(/Notify\[rubyvers\]\/message: defined 'message' as '2/,
                  stdout,
                  "Unexpected Ruby language version for master")
   end
end

step 'Ensure that the ruby subcommand is running under JRuby 9k' do
  on(master, "#{cli} ruby --version") do
    assert_match(/jruby 9\./, stdout,
                 "Unexpected JRuby version for ruby subcommand")
  end
end

step 'Ensure that the irb subcommand is running under JRuby 9k' do
  on(master, "echo 'puts \"VER: \#\{RUBY_VERSION\}\"' | #{cli} irb") do
    assert_match(/^VER: 2\./, stdout,
                 "Unexpected JRuby version for ruby subcommand")
  end
end

step 'Ensure that the gem subcommand is running under JRuby 9k' do
  on(master, "#{cli} gem env") do
    assert_match(/RUBY VERSION: 2\./, stdout,
                 "Unexpected JRuby version for ruby subcommand")
  end
end
