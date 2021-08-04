#!/usr/bin/env bash

set -x
set -e

FORGE_API="forgeapi-cdn.puppet.com"
MODULE_SLUG="puppetlabs-dropsonde"
MODULE_NAME="dropsonde"
MODULE_VERSION="0.0.5"

curl -O "https://${FORGE_API}/v3/files/${MODULE_SLUG}-${MODULE_VERSION}.tar.gz"
mkdir "${DESTDIR}/opt/puppetlabs/server/data/puppetserver/puppetserver_modules"
tar -xvf "${MODULE_SLUG}-${MODULE_VERSION}.tar.gz" -C "${DESTDIR}/opt/puppetlabs/server/data/puppetserver/puppetserver_modules"
mv "${DESTDIR}/opt/puppetlabs/server/data/puppetserver/puppetserver_modules/${MODULE_SLUG}-${MODULE_VERSION}" "${DESTDIR}/opt/puppetlabs/server/data/puppetserver/puppetserver_modules/${MODULE_NAME}"
rm -f "${MODULE_SLUG}-${MODULE_VERSION}.tar.gz"
