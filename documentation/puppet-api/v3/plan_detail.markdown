---
layout: default
title: "Puppet Server: Puppet API: Plan detail"
canonical: "/puppetserver/latest/puppet-api/v3/plan-detail.html"
---

### Uses `application/json` Content-Type

The Content-Type in the response to an plan API query is
`application/json`.

## `GET /puppet/v3/plans/:module/:plan?environment=:environment`

(Introduced in Puppet Server 6.6.0.)

Making a request with no query parameters is not supported and returns an HTTP 400 (Bad
Request) response.

### Supported HTTP Methods

GET

### Supported Formats

JSON

### Query Parameters

Provide one parameter to the GET request:

* `environment`: Only the plan information pertaining to the specified
environment will be returned for the call.

### Responses

#### GET request with results

```
GET /puppet/v3/plans/module/planname?environment=env

HTTP/1.1 200 OK
Content-Type: application/json;charset=utf-8

{
  "metadata": {},
  "name": "module::planname"
}
```

#### GET request for invalid module

If you request details for a plan which cannot be computed because the metadata
is unreadable or it's implementations are not usable Bolt will return an error
response with a status code of 500 containing `kind`, `msg`, and `details` keys.

```
GET /puppet/v3/plans/modulename/planname?environment=env
HTTP/1.1 500 Server Error
Content-Type: application/json

{
    "details": {},
    "kind": "puppet.plans/unparseable-metadata",
    "msg": "unexpected token at '{ \"name\": \"init.sh\" , }\n  ]\n}\n'"
}
```

#### Environment does not exist

If you send a request with an environment parameter that doesn't correspond to the name of a
directory environment on the server, the server returns an HTTP 404 (Not Found) error:

```
GET /puppet/v3/plans/module/planname?environment=doesnotexist

HTTP/1.1 404 Not Found

Could not find environment 'doesnotexist'
```

#### No environment given

```
GET /puppet/v3/plans/module/planname

HTTP/1.1 400 Bad Request

An environment parameter must be specified
```

#### Environment parameter specified with no value

```
GET /puppet/v3/plans/module/planname?environment=

HTTP/1.1 400 Bad Request

The environment must be purely alphanumeric, not ''
```

#### Environment includes non-alphanumeric characters

If the environment parameter in your request includes any characters that are
not `A-Z`, `a-z`, `0-9`, or `_` (underscore), the server returns an HTTP 400 (Bad Request) error:

```
GET /puppet/v3/plans/module/planname?environment=bog|us

HTTP/1.1 400 Bad Request

The environment must be purely alphanumeric, not 'bog|us'
```

#### Module does not exist

If you send a request for a plan in a module that doesn't correspond to the
name of a module on the server, the server returns an HTTP 404 (Not Found)
error:

```
GET /puppet/v3/plans/doesnotexist/planname?environment=env

HTTP/1.1 404 Not Found

Could not find module 'doesnotexist'
```

#### Plan does not exist or does not have a valid name

If you send a request for a plan in that doesn't correspond to the name of a
plan on the server, but the module does exist, the server returns an HTTP 404
(Not Found) error:

```
GET /puppet/v3/plans/module/doesnotexist?environment=env

HTTP/1.1 404 Not Found

Could not find plan 'doesnotexist'
```