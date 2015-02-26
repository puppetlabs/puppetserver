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
  # Temporary solution until beaker is updated to reflect AIO changes for both
  # puppetserver and puppet-agent.  See RE-4044, QENG-1891, etc...
  gem 'beaker', *location_for(ENV['BEAKER_LOCATION'] || 'git://github.com/puppetlabs/beaker#32e123e9a5b719a7bacb8f21661effca099a7717')
  if ENV['GEM_SOURCE'] =~ /rubygems\.delivery\.puppetlabs\.net/
    gem 'sqa-utils', '~> 0.11'
  end
  gem 'httparty'
  gem 'uuidtools'
end

if File.exists? "#{__FILE__}.local"
  eval(File.read("#{__FILE__}.local"), binding)
end
