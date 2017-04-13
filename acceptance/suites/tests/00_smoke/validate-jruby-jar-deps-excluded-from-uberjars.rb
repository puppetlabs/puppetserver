test_name "Validate JRuby jar dependencies are not in packaged uberjars."

install_dir = get_defaults_var(master, "INSTALL_DIR")

install_package(master, "unzip")

jar_files = on(master, "ls #{install_dir}/*.jar").stdout.chomp.split

jar_files.each do |jar_file|
  unzip_grep = "unzip -lf #{jar_file} "
  unzip_grep += "| grep META-INF/jruby.home/lib/ruby"

  on(master, unzip_grep, :acceptable_exit_codes => [0, 1]) do
    step "Validate bouncycastle jars not packaged in #{jar_file}" do
      assert_no_match(/bcpkix.*bcpkix.*\.jar/, stdout,
                      "Found Bouncy Castle pkix jar in #{jar_file}")
      assert_no_match(/bcprov.*bcprov.*\.jar/, stdout,
                      "Found Bouncy Castle prov jar in #{jar_file}")
    end

    step "Validate snakeyaml jar not packaged in #{jar_file}" do
      assert_no_match(/snakeyaml.*snakeyaml.*\.jar/, stdout,
                      "Found snakeyaml jar in #{jar_file}")
    end
  end
end
