#!/usr/bin/env bash

bin_dir="${0%/*}"
acceptance_dir="${bin_dir%/*}"

cd "${acceptance_dir}"

export BEAKER_CONFIG="${BEAKER_CONFIG:-${acceptance_dir}/config/beaker/vcenter_mono.cfg}"

bundle exec rake test:acceptance:beaker["${@}"]
