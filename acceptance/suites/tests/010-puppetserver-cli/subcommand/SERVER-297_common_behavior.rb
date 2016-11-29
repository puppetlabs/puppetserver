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
  assert_equal(env['HTTP_PROXY'], "foo",
               "HTTP_PROXY is missing or has wrong value: '#{env['HTTP_PROXY']}'")
  assert_equal(env['http_proxy'], "foo",
               "http_proxy is missing or has wrong value: '#{env['http_proxy']}'")
  assert_equal(env['HTTPS_PROXY'], "foo",
               "HTTPS_PROXY is missing or has wrong value: '#{env['HTTPS_PROXY']}'")
  assert_equal(env['https_proxy'], "foo",
               "https_proxy is missing or has wrong value: '#{env['https_proxy']}'")
  assert_equal(env['NO_PROXY'], "foo",
               "NO_PROXY is missing or has wrong value: '#{env['NO_PROXY']}'")
  assert_equal(env['no_proxy'], "foo",
               "no_proxy is missing or has wrong value: '#{env['no_proxy']}'")
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
  assert_match(/\bHTTP_PROXY\b\W\W\s\W\bfoo\b/, stdout, "HTTP_PROXY missing or has wrong value")
  assert_match(/\bhttp_proxy\b\W\W\s\W\bfoo\b/, stdout, "http_proxy missing or has wrong value")
  assert_match(/\bHTTPS_PROXY\b\W\W\s\W\bfoo\b/, stdout, "HTTPS_PROXY missing or has wrong value")
  assert_match(/\bhttps_proxy\b\W\W\s\W\bfoo\b/, stdout, "https_proxy missing or has wrong value")
  assert_match(/\bNO_PROXY\b\W\W\s\W\bfoo\b/, stdout, "NO_PROXY missing or has wrong value")
  assert_match(/\bno_proxy\b\W\W\s\W\bfoo\b/, stdout, "no_proxy missing or has wrong value")
end
