#!/bin/bash

set -e

echo "Total memory available: $(grep MemTotal /proc/meminfo | awk '{print $2}')"

lein -U test :all

rake spec
