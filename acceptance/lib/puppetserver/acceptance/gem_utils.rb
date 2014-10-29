def get_gem_list(host, gem_list_command)
  # regex for creating list of (gem, version)
  # NOTE: version here can take a few different forms:
  #   (X.Y.Z)
  #   (Z.Y.Z java)
  #   (X.Y.Z, X.Y.Z2)
  #
  gem_list_regex = Regexp.new('(?<package>[\w-]*) (?<version>.*)')
  array = []
  on(host, "#{gem_list_command}") do
    split_output = stdout.split
    split_output.each do |line|
      match = gem_list_regex.match(line)
      if match
        array << match.captures
      end
    end
  end
  return array
end

