#!/bin/bash

set -x
set -e

PROFILES_FILE="${1:-profiles.clj}"

# Grab the plugins we need to install dependencies from source that are not
# available in public maven repositories.
cat > $PROFILES_FILE <<HEREDOC
{:user {:plugins [[lein-pprint "1.1.2"]
                  [lein-voom "0.1.0-20140716_032004-g85e4c9b" :exclusions [org.clojure/clojure]]
                  [lein-checkouts "1.1.0"]]}}
HEREDOC
