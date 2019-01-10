require 'open3'
require 'open-uri'
require 'json'
require 'pp'

PROJECT_ROOT = File.dirname(__FILE__)
ACCEPTANCE_ROOT = ENV['ACCEPTANCE_ROOT'] ||
  File.join(PROJECT_ROOT, 'acceptance')
BEAKER_OPTIONS_FILE = File.join(ACCEPTANCE_ROOT, 'config', 'beaker', 'options.rb')
PUPPET_SRC = File.join(PROJECT_ROOT, 'ruby', 'puppet')
PUPPET_LIB = File.join(PROJECT_ROOT, 'ruby', 'puppet', 'lib')
PUPPET_SPEC = File.join(PROJECT_ROOT, 'ruby', 'puppet', 'spec')
FACTER_LIB = File.join(PROJECT_ROOT, 'ruby', 'facter', 'lib')
PUPPET_SERVER_RUBY_SRC = File.join(PROJECT_ROOT, 'src', 'ruby', 'puppetserver-lib')
PUPPET_SUBMODULE_PATH = File.join('ruby','puppet')
# Branch of puppetserver for which to update submodule pins
PUPPETSERVER_BRANCH = ENV['PUPPETSERVER_BRANCH'] || 'master'
# Branch of puppet-agent to track for passing puppet SHA
PUPPET_AGENT_BRANCH = ENV['PUPPET_AGENT_BRANCH'] || 'master'

TEST_GEMS_DIR = File.join(PROJECT_ROOT, 'vendor', 'test_gems')
TEST_BUNDLE_DIR = File.join(PROJECT_ROOT, 'vendor', 'test_bundle')

GEM_SOURCE = ENV['GEM_SOURCE'] || "https://artifactory.delivery.puppetlabs.net/artifactory/api/gems/rubygems/"

def assemble_default_beaker_config
  if ENV["BEAKER_CONFIG"]
    return ENV["BEAKER_CONFIG"]
  end

  platform = ENV['PLATFORM']
  layout = ENV['LAYOUT']

  if platform and layout
    beaker_config = "#{ACCEPTANCE_ROOT}/config/beaker/jenkins/"
    beaker_config += "#{platform}-#{layout}.cfg"
  else
    abort "Must specify an appropriate value for BEAKER_CONFIG. See acceptance/README.md"
  end

  return beaker_config
end

def setup_smoke_hosts_config
  sh "bundle exec beaker-hostgenerator centos7-64m-64a > acceptance/scripts/hosts.cfg"
end

def basic_smoke_test(package_version)
  beaker = "PACKAGE_BUILD_VERSION=#{package_version}"
  beaker += " bundle exec beaker --debug --root-keys --repo-proxy"
  beaker += " --preserve-hosts always"
  beaker += " --type aio"
  beaker += " --helper acceptance/lib/helper.rb"
  beaker += " --options-file #{BEAKER_OPTIONS_FILE}"
  beaker += " --load-path acceptance/lib"
  beaker += " --config acceptance/scripts/hosts.cfg"
  beaker += " --keyfile ~/.ssh/id_rsa-acceptance"
  beaker += " --pre-suite acceptance/suites/pre_suite/foss"
  beaker += " --post-suite acceptance/suites/post_suite"
  beaker += " --tests acceptance/suites/tests/00_smoke"

  sh beaker
end

# TODO: this could be DRY'd up with the method above, but it seemed like it
# might make it a little harder to read and didn't seem worth the effort yet
def re_run_basic_smoke_test
  beaker = "bundle exec beaker --debug --root-keys --repo-proxy"
  beaker += " --preserve-hosts always"
  beaker += " --type aio"
  beaker += " --helper acceptance/lib/helper.rb"
  beaker += " --options-file #{BEAKER_OPTIONS_FILE}"
  beaker += " --load-path acceptance/lib"
  beaker += " --config acceptance/scripts/hosts.cfg"
  beaker += " --keyfile ~/.ssh/id_rsa-acceptance"
  beaker += " --tests acceptance/suites/tests/00_smoke"

  sh beaker
end

def jenkins_passing_json_parsed
  passing_url = "http://builds.delivery.puppetlabs.net/passing-agent-SHAs/api/v1/json/report-#{PUPPET_AGENT_BRANCH}"
  uri = URI.parse(passing_url)
  begin
    # DO NOT use uri-open if accepting user input for the uri
    #   we've done some simple correction here,
    #   but not enough to cleanse malicious user input
    jenkins_result = uri.open(redirect: false)
  rescue OpenURI::HTTPError => e
    abort "ERROR: Could not get last passing run data for #{PUPPET_AGENT_BRANCH} of puppet-agent: '#{e.message}'"
  end

  begin
    jenkins_result_parsed = JSON.parse(jenkins_result.read)
  rescue JSON::ParserError => e
    abort "ERROR: Could not get valid json for last passing run of #{PUPPET_AGENT_BRANCH}: '#{e.message}'"
  end
end

def lookup_passing_puppetagent_sha(my_jenkins_passing_json)
  begin
    my_jenkins_passing_json['suite-commit']
  rescue => e
    abort "ERROR: Could not get last passing suite-commit value for #{PUPPET_AGENT_BRANCH}\n\n  #{e}"
  end
end
def lookup_passing_puppet_sha(my_jenkins_passing_json)
  begin
    my_jenkins_passing_json['puppet']
  rescue => e
    abort "ERROR: Could not get puppet's last passing SHA for #{PUPPET_AGENT_BRANCH}\n\n  #{e}"
  end
end

def replace_puppet_pins(passing_puppetagent_sha)
  # read beaker options hash from its file
  puts("replacing puppet-agent SHA in #{BEAKER_OPTIONS_FILE} " \
       "with #{passing_puppetagent_sha}")
  beaker_options_from_file = eval(File.read(BEAKER_OPTIONS_FILE))
  # add puppet-agent version value
  beaker_options_from_file[:puppet_build_version] = passing_puppetagent_sha
  File.write(BEAKER_OPTIONS_FILE, beaker_options_from_file.pretty_inspect)
end

namespace :puppet_submodule do
  desc 'update puppet submodule commit'
  task :update_puppet_version do
    #  ensure we fetch here, or the describe done later could be wrong
    my_jenkins_passing_json = jenkins_passing_json_parsed
    git_checkout_command = "cd #{PUPPET_SUBMODULE_PATH} && git fetch origin && " \
      "git checkout #{lookup_passing_puppet_sha(my_jenkins_passing_json)}"
    puts("checking out known passing puppet version in submodule: `#{git_checkout_command}`")
    system(git_checkout_command)
    # replace puppet-agent sha pin in beaker options file
    replace_puppet_pins(lookup_passing_puppetagent_sha(my_jenkins_passing_json))
  end
  desc 'commit and push; CAUTION: WILL commit and push, upstream, local changes to the puppet submodule and acceptance options'
  task :commit_push do
    git_commit_command = "git checkout #{PUPPETSERVER_BRANCH} && git add #{PUPPET_SUBMODULE_PATH} " \
      "&& git add #{BEAKER_OPTIONS_FILE} && git commit -m '(maint) update puppet submodule version and agent pin'"
    git_push_command = "git checkout #{PUPPETSERVER_BRANCH} && git push origin HEAD:#{PUPPETSERVER_BRANCH}"
    puts "committing submodule and agent pin via: `#{git_commit_command}`"
    system(git_commit_command)
    puts "pushing submodule and agent pin via: `#{git_push_command}`"
    system(git_push_command)
  end
  desc 'update puppet versions and commit and push; CAUTION: WILL commit and push, upstream, local changes to the puppet submodule and acceptance options'
  task :update_puppet_version_w_push => [:update_puppet_version, :commit_push]
end

namespace :spec do
  task :init do
    if ! Dir.exists? TEST_GEMS_DIR
      ## Install bundler
      ## Line 1 launches the JRuby that we depend on via leiningen
      ## Line 2 programmatically runs 'gem install bundler' via the gem command that comes with JRuby
      gem_install_bundler = <<-CMD
      GEM_HOME='#{TEST_GEMS_DIR}' GEM_PATH='#{TEST_GEMS_DIR}' \
      lein run -m org.jruby.Main \
      -e 'load "META-INF/jruby.home/bin/gem"' install -i '#{TEST_GEMS_DIR}' bundler -v '< 2' --no-document --source '#{GEM_SOURCE}'
      CMD
      sh gem_install_bundler

      path = ENV['PATH']
      ## Install gems via bundler
      ## Line 1 makes sure that our local bundler script is on the path first
      ## Line 2 tells bundler to use puppet's Gemfile
      ## Line 3 tells JRuby where to look for gems
      ## Line 4 launches the JRuby that we depend on via leiningen
      ## Line 5 runs our bundle install script
      bundle_install = <<-CMD
      PATH='#{TEST_GEMS_DIR}/bin:#{path}' \
      BUNDLE_GEMFILE='#{PUPPET_SRC}/Gemfile' \
      GEM_HOME='#{TEST_GEMS_DIR}' GEM_PATH='#{TEST_GEMS_DIR}' \
      lein run -m org.jruby.Main \
        -S bundle install --without extra development packaging --path='#{TEST_BUNDLE_DIR}' --retry=3
      CMD
      sh bundle_install
    end
  end
end

desc "Run rspec tests"
task :spec => ["spec:init"] do
  ## Run RSpec via our JRuby dependency
  ## Line 1 tells bundler to use puppet's Gemfile
  ## Line 2 tells JRuby where to look for gems
  ## Line 3 launches the JRuby that we depend on via leiningen
  ## Line 4 adds all our Ruby source to the JRuby LOAD_PATH
  ## Line 5 runs our rspec wrapper script
  ## <sarcasm-font>dang ole real easy man</sarcasm-font>
  run_rspec_with_jruby = <<-CMD
    BUNDLE_GEMFILE='#{PUPPET_SRC}/Gemfile' \
    GEM_HOME='#{TEST_GEMS_DIR}' GEM_PATH='#{TEST_GEMS_DIR}' \
    lein run -m org.jruby.Main \
      -I'#{PUPPET_LIB}' -I'#{PUPPET_SPEC}' -I'#{FACTER_LIB}' -I'#{PUPPET_SERVER_RUBY_SRC}' \
      ./spec/run_specs.rb
  CMD
  sh run_rspec_with_jruby
end

namespace :test do

  namespace :acceptance do
    desc "Run beaker based acceptance tests"
    task :beaker do |t, args|

      # variables that take a limited set of acceptable strings
      type = ENV["BEAKER_TYPE"] || "pe"

      # variables that take pathnames
      beakeropts = ENV["BEAKER_OPTS"] || ""
      presuite = ENV["BEAKER_PRESUITE"] || "#{ACCEPTANCE_ROOT}/suites/pre_suite/#{type}"
      postsuite = ENV["BEAKER_POSTSUITE"] || ""
      helper = ENV["BEAKER_HELPER"] || "#{ACCEPTANCE_ROOT}/lib/helper.rb"
      testsuite = ENV["BEAKER_TESTSUITE"] || "#{ACCEPTANCE_ROOT}/suites/tests"
      loadpath = ENV["BEAKER_LOADPATH"] || ""
      options = ENV["BEAKER_OPTIONSFILE"] || "#{ACCEPTANCE_ROOT}/config/beaker/options.rb"

      # variables requiring some assembly
      config = assemble_default_beaker_config

      beaker = "beaker "

      beaker += " -c #{config}"
      beaker += " --helper #{helper}"
      beaker += " --type #{type}"

      beaker += " --options-file #{options}" if options != ''
      beaker += " --load-path #{loadpath}" if loadpath != ''
      beaker += " --pre-suite #{presuite}" if presuite != ''
      beaker += " --post-suite #{postsuite}" if postsuite != ''
      beaker += " --tests " + testsuite if testsuite != ''

      beaker += " " + beakeropts

      sh beaker
    end

    desc "Do an ezbake build, and then a beaker smoke test off of that build, preserving the vmpooler host"
    task :bakeNbeak do
      package_version = nil

      Open3.popen3("lein with-profile ezbake ezbake build 2>&1") do |stdin, stdout, stderr, thread|
        # sleep 5
        # puts "STDOUT IS: #{stdout}"
        success = true
        stdout.each do |line|
          if match = line.match(%r|^Your packages will be available at http://builds.delivery.puppetlabs.net/puppetserver/(.*)$|)
            package_version = match[1]
          elsif line =~ /^Packaging FAILURE\s*$/
            success = false
          end
          puts line
        end
        exit_code = thread.value
        if success == true
          puts "PACKAGE VERSION IS #{package_version}"
        else
          puts "\n\nPACKAGING FAILED!  exit code is '#{exit_code}'.  STDERR IS:"
          puts stderr.read
          exit 1
        end
      end

      begin
        setup_smoke_hosts_config()
        basic_smoke_test(package_version)
      rescue => e
        puts "\n\nJOB FAILED; PACKAGE VERSION WAS: #{package_version}\n\n"
        raise e
      end
    end

    desc "Do a basic smoke test, using the package version specified by PACKAGE_BUILD_VERSION, preserving the vmpooler host"
    task :smoke do
      package_version = ENV["PACKAGE_BUILD_VERSION"]
      unless package_version
        STDERR.puts("'smoke' task requires PACKAGE_BUILD_VERSION environment variable")
        exit 1
      end
      setup_smoke_hosts_config()
      basic_smoke_test(package_version)
    end

    desc "Re-run the basic smoke test on the host preserved from a previous run of the 'smoke' task"
    task :resmoke do
      re_run_basic_smoke_test()
    end

  end
end

namespace :package do
  task :bootstrap do
    puts 'Bootstrap is no longer needed, using packaging-as-a-gem'
  end
  task :implode do
    puts 'Implode is no longer needed, using packaging-as-a-gem'
  end
end

begin
  require 'packaging'
  Pkg::Util::RakeUtils.load_packaging_tasks
rescue LoadError => e
  puts "Error loading packaging rake tasks: #{e}"
end
