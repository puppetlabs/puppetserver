PROJECT_ROOT = File.dirname(__FILE__)
ACCEPTANCE_ROOT = File.join(PROJECT_ROOT, 'acceptance')
SPEC_TEST_GEMS = 'vendor/spec_test_gems'

def get_platform_map ()
  platform = ENV['PLATFORM']
  arch = ENV['ARCH']

  if platform =~ /(el-|fedora-)(.*)/
    package_type = "rpm"
    config_suffix = "repo"
    platform_name = "#{platform}-#{arch}"
  elsif platform =~ /(debian|ubuntu)(.*)/
    package_type = "deb"
    config_suffix = "list"
    case platform
    when "ubuntu-1004"
      platform_name = "lucid"
    when "ubuntu-1204"
      platform_name = "precise"
    when "debian-6"
      platform_name = "squeeze"
    when "debian-7"
      platform_name = "wheezy"
    else
      abort "Unsupported debian-based platform!"
    end
  end

  {:name => platform_name,
   :platform => platform,
   :arch => arch,
   :package_type => package_type,
   :config_suffix => config_suffix
  }
end

def assemble_default_jvmpuppet_repo_config (platform)
  package_build_name = ENV["PACKAGE_BUILD_NAME"]
  package_build_version = ENV["PACKAGE_BUILD_VERSION"]

  if package_build_name and package_build_version
    repo_config = "http://builds.puppetlabs.lan/"
    repo_config += "#{package_build_name}/#{package_build_version}/"
    repo_config += "repo_configs/#{platform[:package_type]}/"
    repo_config += "pl-#{package_build_name}-#{package_build_version}-"
    repo_config += "#{platform[:name]}.#{platform[:config_suffix]}"
  else
    repo_config = 'http://builds.puppetlabs.lan/jvm-puppet/0.1.1/repo_configs/rpm/pl-jvm-puppet-0.1.1-el-6-x86_64.repo'
  end

  return repo_config
end

def assemble_default_beaker_config (platform)
  if platform[:name] and platform[:arch]
    beaker_config = "#{ACCEPTANCE_ROOT}/config/beaker/jenkins/"
    beaker_config += "#{platform[:platform]}-#{platform[:arch]}.cfg"
  else
    beaker_config = "#{ACCEPTANCE_ROOT}/config/beaker/vbox/el6/64/1host.cfg"
  end

  return beaker_config
end

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
      presuite = ENV["BEAKER_PRESUITE"] || "#{ACCEPTANCE_ROOT}/suites/pre_suite"
      helper = ENV["BEAKER_HELPER"] || "#{ACCEPTANCE_ROOT}/lib/helper.rb"
      testsuite = ENV["BEAKER_TESTSUITE"] || "#{ACCEPTANCE_ROOT}/suites/tests"
      loadpath = ENV["BEAKER_LOADPATH"] || ""

      # variables requiring some assembly
      platform = get_platform_map
      ENV['PLATFORM_NAME'] = platform[:name]

      ENV['JVMPUPPET_REPO_CONFIG'] = ENV["JVMPUPPET_REPO_CONFIG"] ||
        assemble_default_jvmpuppet_repo_config(platform)
      config = ENV["BEAKER_CONFIG"] || assemble_default_beaker_config(platform)

      # variables that take a limited set of acceptable strings
      type = ENV["BEAKER_TYPE"] || "pe"

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
