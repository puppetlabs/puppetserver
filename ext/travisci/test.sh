#!/bin/bash

set -x
set -e

LEIN="${1:-lein2}"

# get_dep_version
# :arg1 is the dependency project whose version we want to grab from project.clj
get_dep_version() {
  local REGEX="puppetlabs\/${1:?}"
  # lein pprint :dependencies, replace all newlines with spaces, using a regex
  # that matches the name followed by quotes with a capturing group inside
  # the quotes that matches anything other than a quote, replace the entire
  # string with that capturing group (which should be the version number) and
  # return that.
  $LEIN pprint :dependencies | tr '\n' ' ' | sed -n "s/^.*$REGEX *\"\([^\"]*\)\".*$/\1/p"
}

# checkout_repo
# :arg1 is the name of the repo to check out
# :arg2 is the artifact id of the project
# :arg3 is the prefix of the git tag associated with the artifact id's version
checkout_repo() {
  local repo=$1
  local depname=$2
  local tag_prefix=$3

  git clone git@github.com:puppetlabs/${repo}.git

  # We need to make sure we are using the correct "release" tag.  It seems
  # unlikely in travisci context, but in the future we may need to check
  # whether this is a SNAPSHOT version and behave differently if so.
  depversion="$(get_dep_version ${depname})"

  if [[ -z $depversion ]]; then
    echo "Could not get a dependency version for: ${depname}, exiting..."
    exit 1
  fi

  pushd ${repo}
  git checkout "${tag_prefix}${depversion}"
  popd
}

# Some of the project's dependencies will not have an artifact published in a
# public repository that lein can automatically pull down and run.  For those,
# we need to clone each dependency locally into the ./checkouts directory so we
# can install from source into the local ~/.m2/repository.

rm -rf checkouts && mkdir checkouts
pushd checkouts

# Make a call to checkout_repo for each repo that needs to be checked out and
# built locally.
#
# Each call to checkout_repo should include three arguments:
#
# - repo: Name of the repo to clone, the last portion of the github URL, e.g.,
#         "myproject" in https://github.com/puppetlabs/myproject.
#
# - artifact-id: Typically appears as an argument to the defproject macro in
#                the project.clj file.  For example, this would be "myproject"
#                in (defproject puppetlabs/myproject...
#
# - tag_prefix: Prefix which is typically appended to the artifact's version for
#               the creation of a tag in the project's git repo.  For example,
#               if version "0.1" of the project is tagged in git as
#               "myproject-0.1", the tag prefix entry should include
#               "myproject-".  Use "" as the element if git tags are not created
#               with a prefix.

checkout_repo "trapperkeeper-webserver-jetty9-cve-test" "trapperkeeper-webserver-jetty9" ""

popd

export VOOM_REPOS=$PWD/checkouts

$LEIN voom build-deps
$LEIN checkouts install

$LEIN test :all
rake spec
