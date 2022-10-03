#!/usr/bin/env bash

# Merge driver for resolving conflicts in project.clj and acceptance/config/beaker/options.rb
# Configure with: `git config merge.version-mergeup.driver "/path/to/mergeup.sh %O %A %B"`
# Ensure `project.clj merge=version-mergeup` exists in your attributes file (usually .gitattributes)
echo "Using puppetserver custom version driver"

# Set MERGEUP_DEP_VERSIONS='TRUE' in order to take 'theirs' when resolving dependency version conflicts in project.clj
# Otherwise, we will keep 'our' version of the dependency.
mergeup_dep_versions=${MERGEUP_DEP_VERSIONS:-false}

# $2 - ours - this is where the result should end up
# $1 - common base
# $3 - theirs
git merge-file $2 $1 $3
# `git merge-file` exits with the number of conflicts, negative on error
num_conflicts=$?
if [[ "$num_conflicts" -lt 1 ]]; then
  exit $num_conflicts
fi

# sed's `-i`  flag requires an argument on OSX
sed_i_flag="-i"
if [[ "$OSTYPE" == "darwin"* ]]; then
  sed_i_flag="-i .bak"
fi

# the smallest merge conflict will take up 4 lines, including the conflict markers,
# so start by looking at "<<<<<<<" and the 3 lines following it
lines=3
while [[ "$num_conflicts" -gt 0 ]]; do
  conflict=$(cat $2 | grep -n -A$lines "<<<<<<<" | grep -m 1 -B$lines ">>>>>>>")
  if [[ "$?" -ne 0 ]]; then
    # only increment after there are no more conflicts at that length
    lines=$(( $lines + 1 ))
    continue
  fi
  echo "FOUND CONFLICT"
  echo $conflict
  # an even diff will have an odd number of lines due to the conflict markers
  # but $lines doesn't include the "<<<<<<<" line we start with
  # so an even diff should have an even number of $lines
  if [[ "$(( $lines % 2))" -ne 0 ]]; then
    echo "ERROR: CONFLICT CONTAINS UNEVEN DIFFS, CAN'T RESOLVE"
    exit 1
  fi
  # find the line number for the first line of the conflict
  # (obtained from using `grep -n` above)
  start_line=$(echo $conflict | grep -o -E "^[0-9]+")
  end_line=$(( $start_line + $lines ))
  line_pointer=$start_line
  for ((l=0;l<$lines;l++)); do
    # can skip the first line, since it should always be "<<<<<<<"
    line_pointer=$(( $line_pointer + 1 ))
    echo "READING LINE $line_pointer"
    line_content=$(sed "$line_pointer!d" $2)
    # we can stop when we read the middle of the conflict, since everything should have a match
    if echo $line_content | grep "=======" ; then
      break
    # TO DO: make 'ps-version' a configurable variable so other projects could use this driver
    elif echo $line_content | grep "ps-version" ; then
      echo "ps-version, keep ours"
    # check if it's a clj-parent bump and keep ours
    elif echo $line_content | grep "clj-parent-version" ; then
      echo "clj-parent bump, keep ours"
    # check for matching lines, since identical lines sometimes still get caught in conflicts, if they're near enough real conflicts
    # potential for error if the line content happens to match something else that isn't just a single line??
    # but that seems unlikely
    elif [[ "$(echo $conflict | grep -o "$line_content" | wc -l)" -eq 2 ]]; then
      echo "found matching line, doesn't matter, keep ours"
    # see if it's at least a version definition
    elif our_dep=$(echo $line_content | grep "(def .* \".*\")") ; then
      if [[ $mergeup_dep_versions == "TRUE" ]]; then
        echo "looks versiony, take theirs"
        # find their matching version definition so we can replace ours with it
        dep_name=$(echo $line_content | awk '{print $2}')
        # look for match starting after the current line, so we don't match our dep
        their_dep=$(tail -n +$(( $line_pointer + 1 )) $2 | grep -o -m1 "(def $dep_name \".*\")")
        if [[ -z $their_dep ]]; then
          echo "ERROR: COULDN'T FIND THEIR $dep_name, CAN'T RESOLVE"
          exit 1
        fi
        set -e
        sed $sed_i_flag "s/$our_dep/$their_dep/" $2
        set +e
      else
        echo "looks versiony, keep ours"
      fi
    # check for puppet_build_version in beaker options file, keep ours
    elif echo $line_content | grep "puppet_build_version" ; then
      echo "puppet_build_version, keep ours"
    else
      echo "ERROR: DON'T KNOW WHAT TO DO WITH THIS CONFLICT, CAN'T RESOLVE"
      exit 1
    fi
  done
  # everything we want should be on 'our' side, so delete theirs
  set -e
  sed $sed_i_flag "$start_line,$end_line{/<<<<<<</d;}" $2
  sed $sed_i_flag "$start_line,$end_line{/=======/,/>>>>>>>/d;}" $2
  set +e
  num_conflicts=$(( $num_conflicts - 1 ))
done

# confirm there are no more conflict markers
git --no-pager diff --check
if [[ "$?" -ne 0 ]]; then
  echo "ERROR: CONFLICT MARKERS WERE NOT CLEANED UP APPROPRIATELY"
  exit 1
fi

echo "Resolved all conflicts!"
if [[ -e "$2.bak" ]]; then
  rm "$2.bak"
fi
exit 0
