#!/bin/bash

set -x
set -e

lein version

lein with-profile +jruby9k -U test
