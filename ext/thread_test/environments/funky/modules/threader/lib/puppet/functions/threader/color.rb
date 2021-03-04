Puppet::Functions.create_function(:'threader::color') do
  dispatch :color do
  end

  def color
    "The funky color is #{Puppet[:color]}"
  end
end
