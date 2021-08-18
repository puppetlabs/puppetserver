#!/usr/bin/env bash

set -x
set -e

# Run command and retry on failure
# run_cmd CMD
run_cmd() {
  eval $1
  rc=$?

  if test $rc -ne 0; then
    attempt_number=0
    retry=3
    while test $attempt_number -lt $retry; do
      info "Retrying... [$((attempt_number + 1))/$retry]"
      eval $1
      rc=$?

      if test $rc -eq 0; then
        break
      fi

      info "Return code: $rc"
      sleep 1s
      ((attempt_number=attempt_number+1))
    done
  fi

  return $rc
}

FORGE_API="forgeapi-cdn.puppet.com"
MODULE_SLUG="puppetlabs-dropsonde"
MODULE_NAME="dropsonde"
INSTALL_DIR=$DESTDIR/opt/puppetlabs/server/data/puppetserver/puppetserver_modules
INSTALL_DROPSONDE_GEM_SCRIPT_DIR=$DESTDIR/opt/puppetlabs/server/data/puppetserver/install_dropsonde

# fetch dropsopnde module tarball link for download
MODULE_FILE_URI=$(curl "https://${FORGE_API}/v3/modules/${MODULE_SLUG}" | jq '.current_release.file_uri' -r)

# download dropsonde module
run_cmd "curl -O https://${FORGE_API}${MODULE_FILE_URI}"

# create dropsonde module location
mkdir $INSTALL_DIR

# install the module
tar -xvf $MODULE_SLUG-* -C $INSTALL_DIR
mv $INSTALL_DIR/$MODULE_SLUG-* $INSTALL_DIR/$MODULE_NAME

# remove the tarball
rm -f $MODULE_SLUG-*

# create location for install dropsonde gem script
mkdir $INSTALL_DROPSONDE_GEM_SCRIPT_DIR

# generate the install manifest
cat <<EOF >> $INSTALL_DROPSONDE_GEM_SCRIPT_DIR/install.pp
class { 'dropsonde':
  use_cron => false,
}
EOF

# set read permissions to the install manifest
chmod +r $INSTALL_DROPSONDE_GEM_SCRIPT_DIR/install.pp

# generate the install script
cat <<EOF >> $INSTALL_DROPSONDE_GEM_SCRIPT_DIR/install.sh
#!/usr/bin/env bash

eval "/opt/puppetlabs/puppet/bin/puppet apply ${INSTALL_DROPSONDE_GEM_SCRIPT_DIR}/install.pp"

if test $? -eq 0; then
  echo "dropsonde was successfully installed!"
else
  echo "dropsonde failed to install!"
fi
EOF

# set exec permissions to the install script
chmod +x $INSTALL_DROPSONDE_GEM_SCRIPT_DIR/install.sh
