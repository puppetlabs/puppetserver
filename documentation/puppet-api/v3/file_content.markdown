---
layout: default
title: "Puppet Server: Puppet API: File Content"
canonical: "/puppetserver/latest/puppet-api/v3/file_content.html"
---

The `file_content` endpoint returns contents of the specified file.

## `GET /puppet/v3/file_content/:mount_point/:module/:file-path?environment=:environment`

When specifying environment see the [open source puppet API docs](https://puppet.com/docs/puppet/latest/http_api/http_file_content.html)

## `GET /puppet/v3/file_content/:mount_point/:module/:file-path?project=:project-ref

Return the contents of a file from the project specified by :project-ref, which should be of the form :name-:version

The `:mount_point` specifies where to look inside modules to find the `:file-path`.

 - `modules` - Find `:file-path` under the `files` subdirectory of `:module`
 - `tasks` - Find `:file-path` under the `tasks` subdirectory of `:module`

### Response

A successful request to this endpoint returns an `HTTP 200` response code and
`application/octet-stream` Content-Type header, and the contents of the specified file
in the response body. An unsuccessful request returns an error response
code with a `text/plain` Content-Type header:

-   400: returned when neither the environment nor the project query parameters are provided, or when both project and environment are provided. 
-   404: returned when requesting a file that is not within a module's `files` or `tasks`
directory, or when any other component (project, module, or mount point) is not found.

