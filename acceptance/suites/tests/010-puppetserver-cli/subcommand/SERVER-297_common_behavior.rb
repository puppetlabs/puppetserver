require 'test/unit/assertions'
require 'json'

test_name "Puppetserver subcommand consolidated ENV handling tests."

proxy_env_vars = "HTTP_PROXY=foo http_proxy=foo HTTPS_PROXY=foo https_proxy=foo NO_PROXY=foo no_proxy=foo"

step "ruby: Check that PATH, HOME, GEM_HOME JARS_REQUIRE and JARS_NO_REQUIRE are present"
on(master, "puppetserver ruby -rjson -e 'puts JSON.pretty_generate(ENV.to_hash)'") do
  env = JSON.parse(stdout)
  assert(env['PATH'], "PATH missing")
  assert(env['HOME'], "HOME missing")
  assert(env['GEM_HOME'], "GEM_HOME missing")
  assert(env['JARS_REQUIRE'], "JARS_REQUIRE missing")
  assert(env['JARS_NO_REQUIRE'], "JARS_NO_REQUIRE missing")
end

step "ruby: Check that proxy env-variables are present"
on(master, "#{proxy_env_vars} puppetserver ruby -rjson -e 'puts JSON.pretty_generate(ENV.to_hash)'") do
  env = JSON.parse(stdout)
  assert(env['HTTP_PROXY'], "HTTP_PROXY is missing")
  assert(env['http_proxy'], "http_proxy is missing")
  assert(env['HTTPS_PROXY'], "HTTPS_PROXY is missing")
  assert(env['https_proxy'], "https_proxy is missing")
  assert(env['NO_PROXY'], "NO_PROXY is missing")
  assert(env['no_proxy'], "no_proxy is missing")
  assert_nil(env['RANDOM_VARIABLE'], "RANDOM_VARIABLE is present and should not be")
end

step "irb: Check that PATH, HOME, GEM_HOME JARS_REQUIRE and JARS_NO_REQUIRE are present"
on(master, "echo 'puts JSON.pretty_generate(ENV.to_hash)' | puppetserver irb -f -rjson") do
  assert_match(/\bPATH\b/, stdout, "PATH missing")
  assert_match(/\bHOME\b/, stdout, "HOME missing")
  assert_match(/\bGEM_HOME\b/, stdout, "GEM_HOME missing")
  assert_match(/\bJARS_REQUIRE\b/, stdout, "JARS_REQUIRE missing")
  assert_match(/\bJARS_NO_REQUIRE\b/, stdout, "JARS_NO_REQUIRE missing")
end

step "irb: Check that proxy env-variables are present"
on(master, "echo 'puts JSON.pretty_generate(ENV.to_hash)' | #{proxy_env_vars} puppetserver irb -f -rjson") do
  assert_match(/\bHTTP_PROXY\b/, stdout, "HTTP_PROXY missing")
  assert_match(/\bhttp_proxy\b/, stdout, "http_proxy missing")
  assert_match(/\bHTTPS_PROXY\b/, stdout, "HTTPS_PROXY missing")
  assert_match(/\bhttps_proxy\b/, stdout, "https_proxy missing")
  assert_match(/\bNO_PROXY\b/, stdout, "NO_PROXY missing")
  assert_match(/\bno_proxy\b/, stdout, "no_proxy missing")
end
