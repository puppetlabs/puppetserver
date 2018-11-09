source ENV['GEM_SOURCE'] || 'https://artifactory.delivery.puppetlabs.net/artifactory/api/gems/rubygems/'

def location_for(place, fake_version = nil)
  if place =~ /^(git[:@][^#]*)#(.*)/
    [fake_version, { :git => $1, :branch => $2, :require => false }].compact
  elsif place =~ /^file:\/\/(.*)/
    ['>= 0', { :path => File.expand_path($1), :require => false }]
  else
    [place, { :require => false }]
  end
end

gem 'packaging', *location_for(ENV['PACKAGING_LOCATION'] || '~> 0.99')
gem 'rake', :group => [:development, :test]
gem 'jira-ruby', :group => :development

group :test do
  gem 'rspec'
  gem 'beaker', *location_for(ENV['BEAKER_VERSION'] || '~> 4.1')
  gem "beaker-hostgenerator", *location_for(ENV['BEAKER_HOSTGENERATOR_VERSION'] || "~> 1.1")
  gem "beaker-abs", *location_for(ENV['BEAKER_ABS_VERSION'] || "~> 0.1")
  gem "beaker-vmpooler", *location_for(ENV['BEAKER_VMPOOLER_VERSION'] || "~> 1.3")
  gem "beaker-puppet", *location_for(ENV['BEAKER_PUPPET_VERSION'] || ["~> 1.0", ">= 1.0.1"])
  gem 'uuidtools'
  gem 'httparty'
  gem 'master_manipulator'

  # docker-api 1.32.0 requires ruby 2.0.0
  gem 'docker-api', '1.31.0'
end

if File.exists? "#{__FILE__}.local"
  eval(File.read("#{__FILE__}.local"), binding)
end
