#!/usr/bin/env bash

VENDORED_GEMS_HOME="./target/jruby-gem-home"

gem_list=()
while read LINE
do
  gem_name=$(echo $LINE |awk '{print $1}')
  gem_version=$(echo $LINE |awk '{print $2}')
  gem_list+=("$gem_name:$gem_version")
done < ./resources/ext/build-scripts/jruby-gem-list.txt

echo "Installing vendored gems to '${VENDORED_GEMS_HOME}'"
lein gem install --install-dir "${VENDORED_GEMS_HOME}" --no-ri --no-rdoc "${gem_list[@]}"
