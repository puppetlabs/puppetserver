#!/usr/bin/env bash

set -x
set -e

SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SOURCE" ]; do # resolve $SOURCE until the file is no longer a symlink
  DIR="$( cd -P "$( dirname "$SOURCE" )" && pwd )"
  SOURCE="$(readlink "$SOURCE")"
  [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE" # if $SOURCE was a relative symlink, we need to resolve it relative to the path where the symlink file was located
done
DIR="$( cd -P "$( dirname "$SOURCE" )" && pwd )"

echo "Installing JRuby vendor gems"
cat "${DIR}/jruby-gem-list.txt"

echo "jruby-puppet: { gem-home: ${DESTDIR}/opt/puppetlabs/server/data/puppetserver/vendored-jruby-gems }" > jruby.conf

gem_list=()
while read LINE
do
  gem_name=$(echo $LINE |awk '{print $1}')
  gem_version=$(echo $LINE |awk '{print $2}')
  gem_list+=("$gem_name:$gem_version")
done < "${DIR}/jruby-gem-list.txt"
java -cp puppet-server-release.jar:jruby-9k.jar clojure.main -m puppetlabs.puppetserver.cli.gem --config jruby.conf -- install --no-ri --no-rdoc "${gem_list[@]}"


echo "Installing MRI vendor gems"
cat "${DIR}/mri-gem-list.txt"

echo "jruby-puppet: { gem-home: ${DESTDIR}/opt/puppetlabs/puppet/lib/ruby/vendor_gems }" > jruby.conf

gem_list=()
while read LINE
do
  gem_name=$(echo $LINE |awk '{print $1}')
  gem_version=$(echo $LINE |awk '{print $2}')
  gem_list+=("$gem_name:$gem_version")
done < "${DIR}/mri-gem-list.txt"
java -cp puppet-server-release.jar:jruby-9k.jar clojure.main -m puppetlabs.puppetserver.cli.gem --config jruby.conf -- install --no-ri --no-rdoc "${gem_list[@]}"
