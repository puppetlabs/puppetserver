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
