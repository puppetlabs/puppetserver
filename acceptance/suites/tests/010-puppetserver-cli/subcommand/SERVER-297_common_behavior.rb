require 'test/unit/assertions'
require 'json'

test_name "Puppetserver subcommand consolidated ENV handling tests."

step "ruby: Check that PATH, HOME, GEM_HOME JARS_REQUIRE and JARS_NO_REQUIRE are present"
on(master, "puppetserver ruby -rjson -e 'puts JSON.pretty_generate(ENV.to_hash)'") do
  env = JSON.parse(stdout)
  assert(env['PATH'], "PATH missing")
  assert(env['HOME'], "HOME missing")
  assert(env['GEM_HOME'], "GEM_HOME missing")
  assert(env['JARS_REQUIRE'], "JARS_REQUIRE missing")
  assert(env['JARS_NO_REQUIRE'], "JARS_NO_REQUIRE missing")
end

step "irb: Check that PATH, HOME, GEM_HOME JARS_REQUIRE and JARS_NO_REQUIRE are present"
on(master, "echo 'puts JSON.pretty_generate(ENV.to_hash)' | puppetserver irb -f -rjson") do
  assert_match(/\bPATH\b/, stdout, "PATH missing")
  assert_match(/\bHOME\b/, stdout, "HOME missing")
  assert_match(/\bGEM_HOME\b/, stdout, "GEM_HOME missing")
  assert_match(/\bJARS_REQUIRE\b/, stdout, "JARS_REQUIRE missing")
  assert_match(/\bJARS_NO_REQUIRE\b/, stdout, "JARS_NO_REQUIRE missing")
end
