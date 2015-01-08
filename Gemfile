source ENV['GEM_SOURCE'] || "https://rubygems.org"

gem 'rake', :group => [:development, :test]
gem 'jira-ruby', :group => :development

group :test do
  gem 'rspec'
  gem 'beaker', '~>1.17.0'
  if ENV['GEM_SOURCE'] =~ /rubygems\.delivery\.puppetlabs\.net/
    gem 'sqa-utils'
  end
end
