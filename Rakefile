PROJECT_ROOT = File.dirname(__FILE__)
ACCEPTANCE_ROOT = File.join(PROJECT_ROOT, 'acceptance')
SPEC_TEST_GEMS = 'vendor/spec_test_gems'

JVMPUPPET_REPO_CONFIG_DEFAULT = 'http://builds.puppetlabs.lan/jvm-puppet/0.1.1/repo_configs/rpm/pl-jvm-puppet-0.1.1-el-6-x86_64.repo'

task :init do
  ## Download any gems that we need for running rspec
  ## Line 1 launches the JRuby that we depend on via leiningen
  ## Line 2 programmatically runs 'gem install rspec' via the gem command that comes with JRuby
  gem_install_rspec = <<-CMD
    lein run -m org.jruby.Main \
    -e 'load "META-INF/jruby.home/bin/gem"' install -i '#{SPEC_TEST_GEMS}' --no-rdoc --no-ri rspec
  CMD
  sh gem_install_rspec unless Dir.exists? SPEC_TEST_GEMS
end

task :spec => [:init] do
  puppet_lib = File.join(PROJECT_ROOT, 'ruby', 'puppet', 'lib')
  facter_lib = File.join(PROJECT_ROOT, 'ruby', 'facter', 'lib')
  ruby_src = File.join(PROJECT_ROOT, 'src', 'ruby', 'jvm-puppet-lib')

  ## Run RSpec via our JRuby dependency
  ## Line 1 tells JRuby where to look for gems
  ## Line 2 launches the JRuby that we depend on via leiningen
  ## Line 3 adds all our Ruby source to the JRuby LOAD_PATH
  ## Line 4 programmatically runs 'rspec ./spec' in JRuby
  run_rspec_with_jruby = <<-CMD
    GEM_HOME='#{SPEC_TEST_GEMS}' GEM_PATH='#{SPEC_TEST_GEMS}' \
    lein run -m org.jruby.Main \
    -I'#{puppet_lib}' -I'#{facter_lib}' -I'#{ruby_src}' \
    -e 'require \"rspec\"; RSpec::Core::Runner.run(%w[./spec], $stderr, $stdout)'
  CMD
  sh run_rspec_with_jruby
end


namespace :test do

  namespace :acceptance do
    desc "Run beaker based acceptance tests"
    task :beaker do |t, args|

      # variables that take pathnames
      beakeropts = ENV["BEAKER_OPTS"] || ""
      config = ENV["BEAKER_CONFIG"] || "#{ACCEPTANCE_ROOT}/config/beaker/vbox/el6/64/1host.cfg"
      presuite = ENV["BEAKER_PRESUITE"] || "#{ACCEPTANCE_ROOT}/suites/pre_suite"
      helper = ENV["BEAKER_HELPER"] || "#{ACCEPTANCE_ROOT}/lib/helper.rb"
      testsuite = ENV["BEAKER_TESTSUITE"] || "#{ACCEPTANCE_ROOT}/suites/tests"
      loadpath = ENV["BEAKER_LOADPATH"] || ""

      # variables that take a limited set of acceptable strings
      type = ENV["BEAKER_TYPE"] || "pe"

      # must-have variables
      if not ENV['JVMPUPPET_REPO_CONFIG']
        ENV['JVMPUPPET_REPO_CONFIG'] = JVMPUPPET_REPO_CONFIG_DEFAULT
      end

      jvmpuppet_package = ENV['JVMPUPPET_REPO_CONFIG']
      if not jvmpuppet_package && presuite != ''
        abort("Must set environment variable, 'JVMPUPPET_REPO_CONFIG' to run the pre_suite.")
      end

      beaker = "beaker "

      beaker += " -c #{config}"
      beaker += " --helper #{helper}"
      beaker += " --type #{type}"

      beaker += " --load-path #{loadpath}" if loadpath != ''
      beaker += " --pre-suite #{presuite}" if presuite != ''
      beaker += " --tests " + testsuite if testsuite != ''

      beaker += " " + beakeropts

      sh beaker
    end
  end
end
