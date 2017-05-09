#!/bin/bash

set -x
set -e

lein with-profile +jruby9k run -m org.jruby.Main -e 'load "META-INF/jruby.home/bin/rake"' spec
