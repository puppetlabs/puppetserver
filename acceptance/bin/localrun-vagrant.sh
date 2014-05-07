#!/usr/bin/env bash

bin_dir="${0%/*}"
acceptance_dir="${bin_dir%/*}"

export BEAKER_CONFIG="${BEAKER_CONFIG:-${acceptance_dir}/config/beaker/vbox/el6/64/1host.cfg}"

bundle exec rake test:acceptance:beaker["${@}"]

