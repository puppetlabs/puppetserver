#!/usr/bin/env bash

# Source this file to interpret build parameters passed in to jenkins jobs for
# acceptance tests. This needs to happen on each acceptance test job
# individually (of which there are currently planned to be 6) because each job
# has its own platform matrix.

set -x
set -e

PACKAGE_BUILD_NAME="${PACKAGE_BUILD_NAME:?}"
PACKAGE_BUILD_VERSION="${PACKAGE_BUILD_VERSION:?}"

export PACKAGE_BUILD_OUTPUT="http://builds.puppetlabs.lan/${PACKAGE_BUILD_NAME}/${PACKAGE_BUILD_VERSION}"

#-------------------------------------------------------------------------------
# Possible PLATFORM values for ezbake+packaging

# PLATFORM_TYPE: deb
# CONFIG_SUFFIX: .list
# lucid
# precise
# quantal
# saucy
# squeeze
# stable
# testing
# wheezy
 
# PLATFORM_TYPE: rpm
# CONFIG_SUFFIX: .repo
# el-5-SRPMS
# el-5-i386
# el-5-x86_64
# el-6-SRPMS
# el-6-i386
# el-6-x86_64
# fedora-f19-SRPMS
# fedora-f19-i386
# fedora-f19-x86_64

TEST="$(echo ${PLATFORM:?} | egrep '(el-|fedora-)')"

if [ "$?" == "0" ] ;then # fedora or el6 platform
	PACKAGE_TYPE="rpm"
	CONFIG_SUFFIX="repo"
else # debian or ubuntu platform
	PACKAGE_TYPE="deb"
	CONFIG_SUFFIX="list"
fi

export JVMPUPPET_REPO_CONFIG="${PACKAGE_BUILD_OUTPUT}/repo_configs/${PACKAGE_TYPE}/pl-${PACKAGE_BUILD_NAME}-${PACKAGE_BUILD_VERSION}-${PLATFORM}.${CONFIG_SUFFIX}"

#
cat > .props <<DOWNSTREAM_BUILD_PARAMETERS
JVMPUPPET_REF=${JVMPUPPET_REF}
PACKAGE_BUILD_NAME=${PACKAGE_BUILD_NAME}
PACKAGE_BUILD_VERSION=${PACKAGE_BUILD_VERSION}
DOWNSTREAM_BUILD_PARAMETERS

set +x
