Puppet::Functions.create_function(:'threader::futurist') do
  dispatch :futurist do
  end

  def futurist
    Puppet[:future_features] = true
    "The funky future is #{Puppet[:future_features]}"
  end
end
