source ENV['GEM_SOURCE'] || "https://rubygems.org"

group :test do
  gem 'rake'
  gem 'rspec'
  gem 'beaker', '~>1.20.0'
  if ENV['GEM_SOURCE'] =~ /rubygems\.delivery\.puppetlabs\.net/
    gem 'sqa-utils'
  end
end

