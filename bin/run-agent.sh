#!/bin/sh

export RUBYLIB="$PWD/ruby/puppet/lib:$PWD/ruby/facter/lib"
export PATH=$PWD/ruby/puppet/bin:$PWD/ruby/facter/bin:$PATH

CERTNAME=${1:-myagent}

puppet agent --no-daemonize --debug --trace --verbose \
            --confdir=./scratch/agent/conf \
            --vardir=./scratch/agent/var \
            --server localhost \
            --certname $CERTNAME \
            --onetime \
            --masterport 8140
