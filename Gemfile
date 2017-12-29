source ENV['GEM_SOURCE'] || "http://rubygems.delivery.puppetlabs.net"

def location_for(place, fake_version = nil)
  if place =~ /^(git[:@][^#]*)#(.*)/
    [fake_version, { :git => $1, :branch => $2, :require => false }].compact
  elsif place =~ /^file:\/\/(.*)/
    ['>= 0', { :path => File.expand_path($1), :require => false }]
  else
    [place, { :require => false }]
  end
end

gem 'rake', :group => [:development, :test]
gem 'jira-ruby', :group => :development

group :test do
  gem 'rspec'
  gem 'beaker', *location_for(ENV['BEAKER_VERSION'] || '~> 3.27')
  gem "beaker-hostgenerator", *location_for(ENV['BEAKER_HOSTGENERATOR_VERSION'] || "~> 0.8")
  gem "beaker-abs", *location_for(ENV['BEAKER_ABS_VERSION'] || "~> 0.1")
  gem "beaker-puppet", *location_for(ENV['BEAKER_PUPPET_VERSION'] || "~> 0.8")
  gem 'uuidtools'
  gem 'httparty'
  gem 'master_manipulator'

  # docker-api 1.32.0 requires ruby 2.0.0
  gem 'docker-api', '1.31.0'
end

if File.exists? "#{__FILE__}.local"
  eval(File.read("#{__FILE__}.local"), binding)
end
