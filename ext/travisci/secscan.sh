#! /usr/bin/env bash

#set -e


# TODO::
# . It would also be nice if it could be passed the pattern as an argument but that may not be feasible 
#    when one considers substring matches
# . For this to be truly generic, it also need to be able to deduce what a comment line looks like
#    based on the extension. Currently it just does that for clojure and defaults to # otherwise.

# There are two ways to go about looking for offending pattern
# 1. Strip of all comment lines and then look
# 2. Identify lines with the offending pattern and then rule out comment lines or 
#    other false positive cases. this seems to be an efficient approach

# This method below is susceptible to missing the problematic patterns if they appear on same lines
# as clojure.edn/read-strings
#find . -name "*.clj" -type f -exec grep -H -n 'read-string' {} + | grep -v -e '(^;|clojure\.edn/read-string)'

# This method can miss out just plain read-string which is actually equivalent to clojure.core/read-string
#find . -name "*.clj" -type f -exec grep -H -n 'clojure\.core/read-string' {} + |\
#	grep -E -v "\.clj:[0-9]+:[[:space:]]*;.*?clojure\.core/read-string"

# Settling for a more safer search that can minimize false negative escapes. 
# Ideally we would have liked all matches with read-string that are not clojure.edn/read-string

# Search for the offending pattern, ignore all comment lines and then match offending pattern again 
# with explicit qualifier prefix of clojure.core.
# It should be possible to combine the first and second steps above to make it efficient
 
# find <folders-to-search> -name <extensions> -type f -exec grep -H -n "read-string" {} + | grep -v -E "\.clj:[0-9]+:[[:space:]]*;.*" | grep -E "[^;][[:alpha:]]*\b([[:space:]]read-string|clojure\.core/read-string)" 


if [ "$#" -lt 2 ]; then
	echo "Usage - <script-name> "list of folders to search" extension-name "
fi

folders=$1
ext=$2

if [ "$ext" == "clj" ]; then
   comment=";"
else
   comment="#"
fi

find "$folders" -name "*.$ext" -type f -exec grep -H -n -E "read-string" {} + |\
	grep -E -v "\.$ext:[0-9]+:[[:space:]]*$comment.*" | grep -E "[^;][[:alpha:]]*\b([[:space:]]+read-string|clojure\.core/read-string)" 

findres=$?

if [ $findres == 0 ]; then
	echo "Last command returned 0. exiting with 1"
	exit 1
elif [ $findres == 1 ]; then
	echo "Last command returned 1. exiting with 0"
	exit 0
fi
