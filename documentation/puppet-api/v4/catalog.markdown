---
layout: default
title: "Puppet Server: Puppet API: Catalog"
canonical: "/puppetserver/latest/puppet-api/v4/catalog.html"
---

The catalog API returns a compiled catalog for the node specified in the request,
making use of provided metadata like facts, environment, and classes if specified.
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
  "classes": [ "<class name>", ... ],
  "parameters": { "<param name>": "<param value>" }
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
The name of the environment for which to compile the catalog. If not provided, defaults
to 'production'. (TODO LOOK THIS UP IN THE CLASSIFIER)

#### `classes`
Any classes that should be applied to the node. Can be omitted, in which case the classes
will be looked up in the configured node classifier. (WHAT HAPPENS IF NONE IS CONFIGURED?
IS THERE A CASE WHERE WE WOULD WANT NEITHER?)

#### `parameters`
Any parameters that should be added to the node. Can be omitted. (ARE THESE PARAMETERS ALA
https://puppet.com/docs/puppet/5.3/lang_classes.html#class-parameters-and-variables
OR PARAMETERS ALA https://github.com/puppetlabs/puppet/blob/master/lib/puppet/node.rb#L29?
Are those two different things or somehow the same/connected? DO THESE ALSO COME FROM THE
CLASSIFIER SOMETIMES?)

### Schema

The catalog response body conforms to the [catalog schema](./catalog.json).

### Authorization

All requests made to the catalog API are authorized using the Trapperkeeper-based `auth.conf`.
For more information about the Puppet Server authorization process and configuration settings,
see the [`auth.conf` documentation][`auth.conf`].
