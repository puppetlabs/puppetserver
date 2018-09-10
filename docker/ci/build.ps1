$ErrorActionPreference = 'Stop'

$BUNDLER_PATH = '.bundle/gems'
$repository = 'pcr-internal.puppet.net/release-engineering'

function build_and_test_image($container_name)
{
  # ===
  # === run linter on the docker files
  # ===
  bundle exec puppet-docker local-lint $container_name

  # ===
  # === build and test $container_name
  # ===
  bundle exec puppet-docker build $container_name --repository $repository
  bundle exec puppet-docker spec $container_name
}

function push_image($container_name) 
{
  bundle exec puppet-docker push $container_name --repository $repository
}

# ===
# === bundle install to get ready
# ===
bundle install --path $BUNDLER_PATH

# ===
# === pull updated base images
# ===
bundle exec puppet-docker update-base-images ubuntu:16.04

$container_list = @('puppetserver-standalone', 'puppetserver')

# ===
# === build and test all the images before we push anything
# ===
$container_list | % { build_and_test_image $_ }

# ===
# === push all the images
# ===
# $container_list | % { push_image $_ }

# ===
# === SUCCESS
# ===
