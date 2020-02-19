Puppet::Functions.create_function(:'threader::doit') do
  dispatch :doit do
  end

  def doit
    'Always on the one'
  end
end
