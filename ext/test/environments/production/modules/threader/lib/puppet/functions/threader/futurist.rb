Puppet::Functions.create_function(:'threader::futurist') do
  dispatch :futurist do
  end

  def futurist
    "The production future is #{Puppet[:future_features]}"
  end
end
