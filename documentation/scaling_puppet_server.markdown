---
layout: default
title: "Scaling Puppet Server with compile masters"
canonical: "/puppetserver/latest/scaling_puppet_server.html"
---

To scale Puppet Server for many thousands of nodes, you'll need to add Puppet masters dedicated to catalog compilation. These Servers are known as **compile masters**, and are simply additional load-balanced Puppet Servers that receive catalog requests from agents and synchronize the results with each other.

> **If you're using Puppet Enterprise (PE),** consult its documentation instead of this guide for PE-specific requirements, settings, and instructions:
>
> -   [Large environment installations (LEI)](https://puppet.com/docs/pe/latest/installing/hardware_requirements.html#large-environment-hardware-requirements)
> -   [Compile masters](https://puppet.com/docs/pe/latest/installing/installing_compile_masters.html)
> -   [Load balancing](https://puppet.com/docs/pe/latest/installing/installing_compile_masters.html#using-load-balancers-with-compile-masters)
> -   [High availability](https://puppet.com/docs/pe/latest/high_availability/high_availability_overview.html)
> -   [Code Manager](https://puppet.com/docs/pe/latest/code_management/code_mgr_how_it_works.html)

## Planning your load-balancing strategy

The rest of your configuration depends on how you plan on distributing the agent load. Determine what your deployment will look like before you add any compile masters, but **implement load balancing as the last step** only after you have the infrastructure in place to support it.

### Using round-robin DNS

Leave all of your agents pointed at the same Puppet Server hostname, then configure your site's DNS to arbitrarily route all requests directed at that hostname to the pool of available masters.

For instance, if all of your agent nodes are configured with `server = puppet.example.com`, configure a DNS name such as:

```
# IP address of master 1:
puppet.example.com. IN A 192.0.2.50
# IP address of master 2:
puppet.example.com. IN A 198.51.100.215
```

For this option, configure your masters with `dns_alt_names` before their certificate request is made.

### Using a hardware load balancer

You can also use a hardware load balancer or a load-balancing proxy webserver to redirect requests more intelligently. Depending on your configuration (for instance, SSL using either raw TCP proxying or acting as its own SSL endpoint), you might also need to use other procedures in this document.

Configuring a load balancer depends on the product, and is beyond the scope of this document.

### Using DNS `SRV` Records

You can use DNS `SRV` records to assign a pool of puppet masters for agents to communicate with. This requires a DNS service capable of `SRV` records, which includes all major DNS software.

> **Note:** This method makes a large number of DNS requests. Request timeouts are completely under the DNS server's control and agents cannot cancel requests early. SRV records don't interact well with static servers set in the config file. Please keep these potential pitfalls in mind when configuring your DNS!

Configure each of your agents with a `srv_domain` instead of a `server` in `puppet.conf`:

```
[main]
use_srv_records = true
srv_domain = example.com
```

Agents will then lookup a `SRV` record at `_x-puppet._tcp.example.com` when they need to talk to a Puppet master.

```
# Equal-weight load balancing between master-a and master-b:
_x-puppet._tcp.example.com. IN SRV 0 5 8140 master-a.example.com.
_x-puppet._tcp.example.com. IN SRV 0 5 8140 master-b.example.com.
```

You can also implement more complex configurations. For instance, if all devices in site A are configured with a `srv_domain` of `site-a.example.com`, and all nodes in site B are configured to `site-b.example.com`, you can configure them to prefer a master in the local site but fail over to the remote site:

```
# Site A has two masters - master-1 is beefier, give it 75% of the load:
_x-puppet._tcp.site-a.example.com. IN SRV 0 75 8140 master-1.site-a.example.com.
_x-puppet._tcp.site-a.example.com. IN SRV 0 25 8140 master-2.site-a.example.com.
_x-puppet._tcp.site-a.example.com. IN SRV 1 5 8140 master.site-b.example.com.

# For site B, prefer the local master unless it's down, then fail back to site A
_x-puppet._tcp.site-b.example.com. IN SRV 0 5 8140 master.site-b.example.com.
_x-puppet._tcp.site-b.example.com. IN SRV 1 75 8140 master-1.site-a.example.com.
_x-puppet._tcp.site-b.example.com. IN SRV 1 25 8140 master-2.site-a.example.com.
```

## Centralizing the Certificate Authority

Additional Puppet Servers should only share the burden of compiling and serving catalogs, which is why they're typically referred to as "compile masters". Any certificate authority functions should be delegated to a single server.

Before you centralize this functionality, ensure that the single server that you want to use as the central CA is reachable at a unique hostname other than (or in addition to) `puppet`. Next, point all agent requests to the centralized CA master, either by configuring each agent or through DNS `SRV` records.

### Directing individual agents to a central CA

On every agent, set the [`ca_server`](https://puppet.com/docs/puppet/latest/configuration.html#caserver) setting in [`puppet.conf`](https://puppet.com/docs/puppet/latest/config_file_main.html) (in the `[main]` configuration block) to the hostname of the server acting as the certificate authority. If you have a large number of existing nodes, it is easiest to do this by managing `puppet.conf` with a Puppet module and a template.

> **Note:** Set this setting *before* provisioning new nodes, or they won't be able to complete their initial agent run.

### Pointing DNS `SRV` records at a central CA

If you [use `SRV` records for agents](#using-dns-srv-records), you can use the `_x-puppet-ca._tcp.$srv_domain` DNS name to point clients to one specific CA server, while the `_x-puppet._tcp.$srv_domain` DNS name handles most of their requests to masters and can point to a set of compile masters.

## Creating and configuring compile masters

To add a compile master to your deployment, begin by [installing and configuring Puppet Server](./install_from_packages.markdown) on it.

Before running `puppet agent` or `puppet master` on the new server:

-   [Disable Puppet Server's certificate authority services](./configuration.markdown#service-bootstrapping).

    -   If you're using the [individual agent configuration method of CA centralization](#directing-individual-agents-to-a-central-ca), set `ca_server` in `puppet.conf` to the hostname of your CA server in the `[main]` config block.
    -   If an `ssldir` is configured, make sure it's configured in the `[main]` block only.

-   If you're using the [DNS round robin method](#using-round-robin-dns) of agent load balancing, or a [load balancer](#using-a-load-balancer) in TCP proxying mode, provide compile masters with certificates using DNS Subject Alternative Names.

    -   Configure `dns_alt_names` in the `[main]` block of `puppet.conf` to cover every DNS name that might be used by an agent to access this master.

        ```
        dns_alt_names = puppet,puppet.example.com,puppet.site-a.example.com
        ```

    -   If the agent or master has been run and already created a certificate, remove it by running `sudo rm -r $(puppet master --configprint ssldir)`. If an agent has requested a certificate from the master, delete it there to re-issue a new one with the alt names: `puppetserver ca clean master-2.example.com`.

-   Request a new certificate by running `puppet agent --test --waitforcert 10`.

-   Log into the CA server and run `puppetserver ca sign master-2.example.com`.

    Add `--allow-dns-alt-names` to the command if `dns_alt_names` were in the certificate request.

## Centralizing reports, inventory service, and catalog searching (storeconfigs)

If you use an HTTP report processor, point all of your Puppet masters at the same shared report server in order to see all of your agents' reports.

If you use the inventory service or exported resources, use PuppetDB and point all of your Puppet masters at a shared PuppetDB instance. A reasonably robust PuppetDB server can handle many Puppet masters and many thousands of agents.

See the [PuppetDB documentation](https://puppet.com/docs/puppetdb/latest/) for instructions on deploying a PuppetDB server, then configure every Puppet master to use it. Note that every Puppet master will need to have its own [whitelist entry](https://puppet.com/docs/puppetdb/latest/configure.html#certificate-whitelist) if you're using HTTPS certificates for authorization.

## Keeping manifests and modules synchronized across masters

You must ensure that all Puppet masters have identical copies of your manifests, modules, and [external node classifier](https://puppet.com/docs/puppet/latest/nodes_external.html) data. Examples include:

-   Using a version control system such as [r10k](https://github.com/puppetlabs/r10k), Git, Mercurial, or Subversion to manage and sync your manifests, modules, and other data.
-   Running an out-of-band `rsync` task via `cron`.
-   Configuring `puppet agent` on each master node to point to a designated model Puppet master, then use Puppet itself to distribute the modules.

## Implementing load distribution

Now that your other compile masters are ready, you can implement your [agent load-balancing strategy](#planning-your-load-balancing-strategy).