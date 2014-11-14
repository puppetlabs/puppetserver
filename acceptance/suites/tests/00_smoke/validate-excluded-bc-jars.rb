
test_name "Validate excluded BC jars are not in packaged uberjar."

if master.is_pe?
  package_name = "pe-puppetserver"
else
  package_name = "puppetserver"
end

install_dir = get_defaults_var(master, "INSTALL_DIR")

jarfile = File.join(install_dir, "puppet-server-release.jar")

install_package(master, "unzip")

unzip_grep = "unzip -lf #{jarfile} "
unzip_grep += "| grep META-INF/jruby.home/lib/ruby/shared"

on(master, unzip_grep, :acceptable_exit_codes => [0,1]) do
  assert_no_match(/bcpkix.*bcpkix.*\.jar/, stdout, "Found Bouncy Castle jars in #{jarfile}")
  assert_no_match(/bcprov.*bcprov.*\.jar/, stdout, "Found Bouncy Castle jars in #{jarfile}")
end
