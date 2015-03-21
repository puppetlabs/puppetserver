source ENV['GEM_SOURCE'] || "https://rubygems.org"

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
  # gem 'beaker', *location_for(ENV['BEAKER_LOCATION'] || '~> 2.2')
  # See: SERVER-435 for the reasoning behind this specific version
  gem 'beaker', *location_for(ENV['BEAKER_LOCATION'] || 'git://github.com/puppetlabs/beaker#beaker2.7.1')
  if ENV['GEM_SOURCE'] =~ /rubygems\.delivery\.puppetlabs\.net/
    gem 'sqa-utils', '~> 0.11'
  end
  gem 'httparty'
  gem 'uuidtools'
end

if File.exists? "#{__FILE__}.local"
  eval(File.read("#{__FILE__}.local"), binding)
end
