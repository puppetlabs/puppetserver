#!/bin/bash

# Configure puppet to use a certificate autosign script (if it exists)
# AUTOSIGN=true|false|path_to_autosign.conf
if test -n "${AUTOSIGN}" ; then
  puppet config set autosign "$AUTOSIGN" --section master
fi
