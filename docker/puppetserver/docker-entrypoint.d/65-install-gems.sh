#!/bin/sh

# Install gems before puppetserver starts
# PUPPETSERVER_GEMS="debouncer vault"
if test -n "${PUPPETSERVER_GEMS}" ; then
  puppetserver gem install --no-document ${PUPPETSERVER_GEMS}
fi
