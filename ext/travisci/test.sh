#!/bin/bash

set -e

# INFO FOR DEBUGGING PURPOSES
set -x
lsb_release -a
java -version
set +x
echo "Total memory available: $(grep MemTotal /proc/meminfo | awk '{print $2}')"
##############################

git submodule update --recursive --init
lein clean
rm -rf vendor

./dev/install-test-gems.sh

if [ "$MULTITHREADED" = "true" ]; then
  filter=":multithreaded"
else
  filter=":singlethreaded"
fi
test_command="lein -U $ADDITIONAL_LEIN_ARGS test $filter"
echo $test_command
$test_command

rake spec
