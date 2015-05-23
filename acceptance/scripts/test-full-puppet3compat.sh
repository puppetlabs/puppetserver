#!/bin/bash
set -x

# command line parameters
export GENCONFIG_LAYOUT="${GENCONFIG_LAYOUT:-redhat6-64ma-debian6-64a-windows2008r2-64a}"
export BEAKER_TESTSUITE="${BEAKER_TESTSUITE:-acceptance/suites/puppet3_tests}"
export BEAKER_PRESUITE="acceptance/suites/pre_suite/puppet3_compat"

bash ./acceptance/scripts/generic/testrun-full.sh
