#!/bin/sh

mkdir -p scratch/agent/{conf,var}
mkdir -p scratch/master/var/client_yaml/facts
mkdir -p scratch/master/conf
cp test-resources/config/master/conf/auth.conf scratch/master/conf/
cp test-resources/config/master/conf/puppet.conf scratch/master/conf/

