#!/usr/bin/env bash

bin_dir="${0%/*}"
acceptance_dir="${bin_dir%/*}"

# This script assumes there is a prepared CentOS6 VirtualBox VM with the
# following changes from initial install:
#
# * Downloaded VMWARE image from http://int-resources.ops.puppetlabs.net/pe-supported-virtual-machines/centos6-64.vmwarevm.tar.bz2
# * Setup Host-Only Networking
# * install avahi, avahi-tools, lsof, man, openssh-server, curl, vim (actually
# some of these aren't necessary, but they are nice).
# * add wayne@pathways to root authorized_keys
# * modify /etc/sysonfig/network (HOSTNAME=centos6-64-1.local)
# * adds /et/sysconfig/network-scripts/ifcfg-eth1
# * modify /etc/ssh/sshd_config "UseDNS no"
# * modify /etc/rc.local to start /etc/init.d/messagebus and /etc/init.d/avahi-daemon
# * modify /etc/sudoers, comment out "requiretty" line
#
# It also assumes the host system has both dnsmasq and avahi-daemon running with
# a local domain named ".local". Confluence wiki currently documents the dnsmasq
# setup to use ".vm":
#  https://confluence.puppetlabs.com/display/DEL/Create+a+Private+NAT+in+VirtualBox
#

VBOX_MACHINE_NAME=PL-vmware-centos-64
VBOX_STATE_NAME=savedstate

export BEAKER_CONFIG="${acceptance_dir}/config/beaker/local/el6/1host.cfg"

VBoxManage controlvm "${VBOX_MACHINE_NAME:?}" poweroff

VBoxManage snapshot "${VBOX_MACHINE_NAME:?}" restore "${VBOX_STATE_NAME:?}" && \
	VBoxManage startvm --type headless "${VBOX_MACHINE_NAME:?}" && \
	sleep 1 && \
	bundle exec rake test:acceptance:beaker["${@}"]
