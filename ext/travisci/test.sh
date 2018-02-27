#!/bin/bash

set -e

export PUPPETSERVER_HEAP_SIZE=4G

echo "Using heap size: $PUPPETSERVER_HEAP_SIZE"
echo "Total memory available: $(grep MemTotal /proc/meminfo | awk '{print $2}')"

if [ "${JRUBY_VERSION}" = "1.7" ]; then
  echo "Running tests with default JRuby (1.7-based)"
  lein -U test :all
elif [ "${JRUBY_VERSION}" = "9k" ]; then
  echo "Running tests with JRuby 9k"
  lein -U with-profile +jruby9k test :all
fi

rake spec JRUBY_VERSION=${JRUBY_VERSION}
