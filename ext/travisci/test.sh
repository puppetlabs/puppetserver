#!/bin/bash

set -e

lein2 test :all

rake spec
