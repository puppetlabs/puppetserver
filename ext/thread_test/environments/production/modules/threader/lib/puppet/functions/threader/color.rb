Puppet::Functions.create_function(:'threader::color') do
  dispatch :color do
  end

  def color
    Puppet[:color] = false
    "The production color is #{Puppet[:color]}"
  end
end
