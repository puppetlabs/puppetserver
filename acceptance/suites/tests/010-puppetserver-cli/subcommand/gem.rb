require 'test/unit/assertions'
require 'puppetserver/acceptance/gem_utils'

test_name "Puppetserver 'gem' subcommand tests."

cli = "puppetserver"

# define gems to test
gems = ['nokogiri', 'excon']

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
  all_gems_string = gems.join(" ")
  on(master, "#{gem_uninstall} #{all_gems_string}")

  step "Teardown: Remove all gems not required to meet a dependency."
  on(master, "#{gem_cleanup}")
end

step "Clean up gems that are not required to meet a dependency."

on(master, "#{gem_cleanup}")

step "Get initial list of installed gems."

initial_installed_gems = get_gem_list(master, "#{gem_list}")

gems.each do |gem_name|
  step "Install test gem."
  on(master, "#{gem_install} #{gem_name}")

  step "Check that test gem is present."
  on(master, "#{gem_list}") do
    assert(/^#{gem_name}/.match(stdout), "#{gem_name} was not found after installation.")
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
