Puppet::Functions.create_function(:'threader::doit') do
  dispatch :doit do
  end

  def doit
    'do it'
  end
end
