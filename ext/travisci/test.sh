#!/bin/bash

set -e

lein test :all

rake spec
