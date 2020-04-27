---
layout: default
title: "Puppet Server: Puppet API: Compile"
canonical: "/puppetserver/latest/puppet-api/v3/compile.html"
---

[PCore Generic Data]: https://github.com/puppetlabs/puppet-specifications/blob/a87c9967ebd7071e0a548920f3262ac24cc3f4cd/language/data-types/pcore-generic-data.md
[`auth.conf` documentation]: ../../config_file_auth.markdown
[v4 catalog schema]: ../v4/catalog.json

The compile endpoint allows compilation of arbitrary pcore serialized Puppet
Code ASTs (aka parsed, but unevaluated Puppet Code snippets). In addition to
the code AST, the caller must provide the `certname`, `environment`, `facts`,
`trusted_facts`, and `variables` for compilation (the context in which this
AST will be evaluated). Optionally, the caller may provide `job_id`,
`transaction_id`, and configure returned logging. The request body must be
JSON formatted and the caller must accept a JSON response. The server must
not have `rich_data` disabled.


## `POST /puppet/v3/compile`

(Introduced in Puppet Server 6.4.0)

The request body must look like:
```
{
  "code_ast": "<pcore serialized ast>",
  "certname": "<node name>",
  "environment": "<environment name>",
  "facts": { "values": { "<fact name>": <fact value>, ... } },
  "trusted_facts": { "values": { "<fact name>": <fact value>, ... } },
  "variables": { "values": { "<variable name>": <variable value>, ... } },

  # The rest are optional:
  "transaction_uuid": "<uuid string>",
  "job_id": "<id string>",
  "options": { "capture_logs": <true/false>,
               "log_level": <one of debug/info/warning/err>
               "bolt": <true/false> Whether or not to attempt to load bolt
               "boltlib": <String> Path to bolt-modules to prepend to modulepath}
}
```

#### `code_ast` (required)
A parsed string of json encoded [PCore Generic Data][]
objects representing a Puppet Code AST. When `rich_data` is enabled (the
default in Puppet 6), the AST represents an intermediate step when
compiling Puppet Code to a catalog. The returned catalog is equivalent
to a catalog returned with the MIME type `application/vnd.puppet.rich+json`
normally.


#### `certname` (required)
The name of the node for which the AST will be compiled.

#### `environment` (required)
The name of the environment within which to compile the AST.

#### `facts` (required)
A hash with a required `values` key, containing a hash of all the facts for the node.

#### `trusted_facts` (required)
A hash with a required `values` key containing a hash of the trusted facts for a node.
In a normal agent's catalog request, these would be extracted from the cert, but this
endpoint does not require a cert for the node whose catalog is being compiled.

#### `variables` (required)
A hash with a required `values` key, containing a hash of all the variables in scope
for the compilation.

#### `transaction_uuid`
The id for tracking the AST compilation.

#### `job_id`
The id of the orchestrator job that requested this compilation.

#### `options`

A hash of options beyond direct input to compilation.

`capture_logs`
Whether to return the logging events that occurred during compilation.

`log_level`
A string representing the logging level at which log events should be captured. Valid
values are one of "debug", "warn", "info".

### Schema

The response body conforms to the [v4 catalog schema][].

### Authorization

All requests made to the compile API are authorized using the Trapperkeeper-based `auth.conf`.
For more information about the Puppet Server authorization process and configuration settings,
see the [`auth.conf` documentation][].
