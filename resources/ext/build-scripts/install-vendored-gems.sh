#!/usr/bin/env bash

set -x
set -e

install_gems () {
  gem_file=$1
  additional_args=$2

  gem_list=()
  while read LINE
  do
    gem_name=$(echo $LINE |awk '{print $1}')
    gem_version=$(echo $LINE |awk '{print $2}')
    gem_list+=("$gem_name:$gem_version")
  done < $gem_file

  java -cp puppet-server-release.jar:jruby-9k.jar clojure.main -m puppetlabs.puppetserver.cli.gem --config jruby.conf -- install ${additional_args:+"$additional_args"} --no-document "${gem_list[@]}"
}

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

install_gems "${DIR}/jruby-gem-list.txt"

# We need to ignore dependencies to prevent puppetserver-ca from being installed
# with facter2 (from gem dependency resolution) and facter3 (from puppet-agent packages)
# If puppetserver ever loses its dependency on puppet-agent or if puppet-agent ever loses
# facter, this will probably explode.
#
# Unfortunately, even with well-crafted `GEM_HOME`, installing the puppetserver-ca gem
# with `--minimal-deps` or `--conservative` does in fact update dependencies even if they're
# satisfied by already-installed gems. So, `--ignore-dependencies` is our best option here.
# Sorry.
# - Morgan, 04-29-2019
echo "Installing MRI vendor gems (with '--ignore-dependencies')"
cat "${DIR}/mri-gem-list-no-dependencies.txt"

echo "jruby-puppet: { gem-home: ${DESTDIR}/opt/puppetlabs/puppet/lib/ruby/vendor_gems }" > jruby.conf

install_gems "${DIR}/mri-gem-list-no-dependencies.txt" "--ignore-dependencies"

# We need to install Dropsonde into an isolated directory so that its dependencies
# can be installed with it without relying on the gems otherwise shipped by
# puppet-agent or by puppetserver.
echo "Installing Dropsonde into an isolated directory, to prevent dependency conflicts"
cat "${DIR}/dropsonde-gem.txt"

echo "jruby-puppet: { gem-home: ${DESTDIR}/opt/puppetlabs/server/data/puppetserver/dropsonde }" > jruby.conf

install_gems "${DIR}/dropsonde-gem.txt"
