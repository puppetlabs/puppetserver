---
layout: default
title: "Puppet Server: Puppet API: Catalog"
canonical: "/puppetserver/latest/puppet-api/v4/catalog.html"
---

The catalog API returns a compiled catalog for the node specified in the request,
making use of provided metadata like facts or environment if specified.
If not specified, it will attempt to fetch this data from Puppet's configured sources
(usually PuppetDB or a node classifier). The returned catalog is in JSON format,
ready to be parsed and applied by an agent.

## `POST /puppet/v4/catalog`

(Introduced in Puppet Server 6.3.0)

The input data for the catalog to be compiled is submitted as a JSON body with the 
following form:
```
{
  "certname": "<node name>",
  "persistence": { "facts": <true/false>, "catalog": <true/false> },
  # The rest are optional:
  "facts": { "values": { "<fact name>": <fact value>, ... } },
  "trusted_facts": { "values": { "<fact name>": <fact value>, ... } },
  "environment": "<environment name>",
  "transaction_uuid": "<uuid string>",
  "job_id": "<id string>",
  "options": { "prefer_requested_environment": <true/false>,
               "capture_logs": <true/false> }
}
```

#### `certname` (required)
The name of the node for which to compile the catalog.

#### `persistence` (required)
A hash containing two required keys, `facts` and `catalog`, which when set to true will
cause the facts and reports to be stored in PuppetDB, or discarded if set to false.

#### `facts`
A hash with a required `values` key, containing a hash of all the facts for the node.
If not provided, Puppet will attempt to fetch facts for the node from PuppetDB.

#### `trusted_facts`
A hash with a required `values` key containing a hash of the trusted facts for a node.
In a normal agent's catalog request, these would be extracted from the cert, but this
endpoint does not require a cert for the node whose catalog is being compiled. If not
provided, Puppet will attempt to fetch the trusted facts for the node from PuppetDB or
from the provided facts hash.

#### `environment`
The name of the environment for which to compile the catalog. If `prefer_requested_environemnt`
is true, override the classified environment with this param. If it is false, only respect this
if the classifier allows an agent-specified environment.

#### `transaction_uuid`
The id for tracking the catalog compilation and report submission.

#### `job_id`
The id of the orchestrator job that triggered this run.

#### `options`

A hash of options beyond direct input to catalogs.

`prefer_requested_environment`
Whether to always override a node's classified environment with the one supplied in the
request. If this is true and no environment is supplied, fall back to the classified
environment, or finally, 'production'.

`capture_logs`
Whether to return the errors and warnings that occurred during compilation alongside the
catalog in the response body.

### Schema

The catalog response body conforms to the [catalog schema](./catalog.json).

### Authorization

All requests made to the catalog API are authorized using the Trapperkeeper-based `auth.conf`.
For more information about the Puppet Server authorization process and configuration settings,
see the [`auth.conf` documentation](../../config_file_auth.markdown).
