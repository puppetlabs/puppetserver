source ENV['GEM_SOURCE'] || "https://rubygems.org"

gem 'rake', :group => [:development, :test]
gem 'jira-ruby', :group => :development

group :test do
  gem 'rspec'
  gem 'beaker', '~>1.21.0'
  if ENV['GEM_SOURCE'] =~ /rubygems\.delivery\.puppetlabs\.net/
    gem 'sqa-utils', '0.12.1'
  end

  # docker-api 1.32.0 requires ruby 2.0.0
  gem 'docker-api', '1.31.0'
end

if File.exists? "#{__FILE__}.local"
  eval(File.read("#{__FILE__}.local"), binding)
end
