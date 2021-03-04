Puppet::Functions.create_function(:'threader::other_func') do
  dispatch :other_func do
  end

  def other_func
    "I'm a different function"
  end
end
