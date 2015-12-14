test_name 'Validate excluded jars are not in packaged uberjar.'

package_name = options['puppetserver-package']
install_dir = get_defaults_var(master, "INSTALL_DIR")

jarfile = File.join(install_dir, "puppet-server-release.jar")

on(master, "test -e \"#{jarfile}\"")
install_package(master, "unzip")

unzip_grep = "unzip -lf #{jarfile} "
unzip_grep += "| grep META-INF/jruby.home/lib/ruby/stdlib"

step 'Validate bouncycastle jars not packaged in uberjar' do
  on(master, unzip_grep, :acceptable_exit_codes => [0,1]) do
    assert_no_match(/bcpkix.*bcpkix.*\.jar/, stdout, "Found Bouncy Castle jars in #{jarfile}")
    assert_no_match(/bcprov.*bcprov.*\.jar/, stdout, "Found Bouncy Castle jars in #{jarfile}")
  end
end

step 'Validate snakeyaml jar not packaged in uberjar' do
  on(master, unzip_grep, :acceptable_exit_codes => [0,1]) do
    assert_no_match(/snakeyaml.*snakeyaml.*\.jar/, stdout, "Found snakeyaml jar in #{jarfile}")
  end
end
