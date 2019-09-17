---
layout: default
title: "Puppet Server: Puppet API: Plans"
canonical: "/puppetserver/latest/puppet-api/v3/plans.html"
---

### Uses `application/json` Content-Type

The Content-Type in the response to an plan API query is
`application/json`.

## `GET /puppet/v3/plans?environment=:environment`

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
GET /puppet/v3/plans?environment=env

HTTP/1.1 200 OK
Content-Type: application/json;charset=utf-8

[
  {
    "name": "apache::init",
    "environment": [
      {
        "name": "production",
        "code_id": null
      }
    ]
  },
  {
    "name": "apache::announce",
    "environment": [
      {
        "name": "production",
        "code_id": null
      }
    ]
  },
  {
    "name": "graphite",
    "environment": [
      {
        "name": "production",
        "code_id": null
      }
    ]
  }
]
```

#### Environment does not exist

If you send a request with an environment parameter that doesn't correspond to the name of a
directory environment on the server, the server returns an HTTP 404 (Not Found) error:

```
GET /puppet/v3/plans?environment=doesnotexist

HTTP/1.1 404 Not Found

Could not find environment 'doesnotexist'
```

#### No environment given

```
GET /puppet/v3/plans

HTTP/1.1 400 Bad Request

An environment parameter must be specified
```

#### Environment parameter specified with no value

```
GET /puppet/v3/plans?environment=

HTTP/1.1 400 Bad Request

The environment must be purely alphanumeric, not ''
```

#### Environment includes non-alphanumeric characters

If the environment parameter in your request includes any characters that are
not `A-Z`, `a-z`, `0-9`, or `_` (underscore), the server returns an HTTP 400 (Bad Request) error:

```
GET /puppet/v3/plans?environment=bog|us

HTTP/1.1 400 Bad Request

The environment must be purely alphanumeric, not 'bog|us'
```
