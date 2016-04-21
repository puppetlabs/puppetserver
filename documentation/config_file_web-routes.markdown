---
layout: default
title: "Puppet Server Configuration Files: web-routes.conf"
canonical: "/puppetserver/latest/config_file_web-routes.html"
---

The `web-routes.conf` file configures the Puppet Server `web-router-service`, which sets mount points for Puppet Server's web applications. You should not modify these mount points, as Puppet 4 agents rely on Puppet Server mounting them to specific URLs.

For an overview, see [Puppet Server Configuration](./configuration.html). To configure the `webserver` service, see the [`webserver.conf` documentation](./config_file_webserver.html).

## Example

The `web-routes.conf` file looks like this:

~~~
# Configure the mount points for the web apps.
web-router-service: {
    # These two should not be modified because the Puppet 4 agent expects them to
    # be mounted at these specific paths.
    "puppetlabs.services.ca.certificate-authority-service/certificate-authority-service": "/puppet-ca"
    "puppetlabs.services.master.master-service/master-service": "/puppet"
    "puppetlabs.services.legacy-routes.legacy-routes-service/legacy-routes-service": ""

    # This controls the mount point for the Puppet administration API.
    "puppetlabs.services.puppet-admin.puppet-admin-service/puppet-admin-service": "/puppet-admin-api"
}
~~~
