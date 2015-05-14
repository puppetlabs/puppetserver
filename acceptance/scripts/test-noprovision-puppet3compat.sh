#!/bin/bash
set -x

export BEAKER_TESTSUITE="acceptance/suites/puppet3_tests/"

bash ./acceptance/scripts/generic/testrun-noprovisions.sh ${1:-}
