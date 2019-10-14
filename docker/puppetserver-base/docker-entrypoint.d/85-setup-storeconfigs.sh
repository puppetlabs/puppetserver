#!/bin/sh

if [ -n "$PUPPET_STORECONFIGS_BACKEND" ]; then
  puppet config set storeconfigs_backend $PUPPET_STORECONFIGS_BACKEND --section master
fi

if [ -n "$PUPPET_STORECONFIGS" ]; then
  puppet config set storeconfigs $PUPPET_STORECONFIGS --section master
fi

if [ -n "$PUPPET_REPORTS" ]; then
  puppet config set reports $PUPPET_REPORTS --section master
fi

# reset defaults if USE_PUPPETDB is false, but don't overwrite custom settings
if [ "$USE_PUPPETDB" = 'false' ]; then
  if [ "$PUPPET_REPORTS" = 'puppetdb' ]; then
    puppet config set reports log --section master
  fi

  if [ "$PUPPET_STORECONFIGS_BACKEND" = 'puppetdb' ]; then
    puppet config set storeconfigs false --section master
  fi
fi
