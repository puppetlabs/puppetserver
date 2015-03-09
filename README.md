# File Synchronization Service for Puppet Enterprise

[![Build Status](https://magnum.travis-ci.com/puppetlabs/pe-file-sync.svg?token=ApBsaKK1zdeqHwzhXLzw&branch=master)](https://magnum.travis-ci.com/puppetlabs/pe-file-sync)

## Running the services
```
lein run -b dev/bootstrap.cfg -c dev/dev.conf
```
This will launch a Trapperkeeper process containing both the client service and 
the storage service.
