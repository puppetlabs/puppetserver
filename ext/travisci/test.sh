#!/bin/bash

set -e

echo "Total memory available: $(grep MemTotal /proc/meminfo | awk '{print $2}')"

./dev/install-test-gems.sh

lein -U test :all

rake spec
