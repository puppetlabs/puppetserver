require 'puppetserver/acceptance/gem_utils'

test_name "Puppetserver 'gem' subcommand tests."

cli = "puppetserver"

# define gems to test
gems = {'nokogiri' => '1.6.7', 'excon' => '0.45.4'}

additional_gem_source = ENV['GEM_SOURCE']
# define command lines
gem_install = "#{cli} gem install --minimal-deps --force"
if additional_gem_source
  gem_install += " --clear-sources --source #{additional_gem_source}"
end

gem_uninstall = "#{cli} gem uninstall"
gem_list = "#{cli} gem list"
gem_cleanup = "#{cli} gem cleanup"

# teardown
teardown do
  step "Teardown: Remove all installed gems."
  gems.keys.each do |gem_name|
    on(master, "#{gem_uninstall} #{gem_name}")
  end
  step "Teardown: Remove all gems not required to meet a dependency."
  on(master, "#{gem_cleanup}")
end

step "Clean up gems that are not required to meet a dependency."

on(master, "#{gem_cleanup}")

step "Get initial list of installed gems."

initial_installed_gems = get_gem_list(master, "#{gem_list}")

gems.each do |gem_name, gem_version|
  step "Install test gem."
  on(master, "#{gem_install} #{gem_name} -v #{gem_version}")

  step "Check that test gem is present."
  on(master, "#{gem_list}") do
    assert(/^#{gem_name}/.match(stdout), "#{gem_name} was not found after installation.")
  end

  if gem_name == 'excon'
    step "SERVER-1601: Validate use of excon gem successful as puppet user" do
      if options[:type] == 'pe'
        runuser = 'pe-puppet'
      else
        runuser = 'puppet'
      end
      on(master, "su #{runuser} -s /bin/bash -c "\
        "'/opt/puppetlabs/bin/puppetserver ruby "\
        "-rexcon -e \"puts Excon::VERSION\"'") do
        assert_equal(gems['excon'], stdout.strip,
                     "Unexpected output for excon version")
      end
    end
  end

  step "Uninstall test gem."
  on(master, "#{gem_uninstall} #{gem_name}")

  step "Check that test gem is no longer present."
  on(master, "#{gem_list}") do
    assert_no_match(/^#{gem_name}/, stdout, "#{gem_name} was found after uninstallation.")
  end
end

step "Clean up gems that are not required to meet a dependency."

on(master, "#{gem_cleanup}")

step "Verify that current list matchs initial list."

final_installed_gems = get_gem_list(master, "#{gem_list}")

initial_installed_gems.each do |gem_info|
  assert_send([final_installed_gems, :include?, gem_info])
end

step "Verify that gem env operates"
on(master, "#{cli} gem env", :acceptable_exit_codes => [0])

step "Verify that Java cli args passed through to gem command"
on(master, "JAVA_ARGS_CLI=-Djruby.cli.version=true #{cli} gem help") do
  assert_match(/jruby \d\.\d\.\d.*$/, stdout,
               'jruby version not included in gem command output')
end

step "(SERVER-1759) Verify that installing a non-existent gem produces a non-zero exit return value"

gem_name = 'if-this-gem-exists-then-someone-has-a-cruel-sense-of-humor'
on(master, "#{cli} gem install #{gem_name}", :acceptable_exit_codes => [2]) do
  assert_match(/Could not find a valid gem/, stderr)
end
