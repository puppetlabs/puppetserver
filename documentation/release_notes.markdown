---
layout: default
title: "Puppet Server: Release Notes"
canonical: "/puppetserver/latest/release_notes.html"
---

[Trapperkeeper]: https://github.com/puppetlabs/trapperkeeper
[service bootstrapping]: ./configuration.markdown#service-bootstrapping
[auth.conf]: ./config_file_auth.markdown
[puppetserver.conf]: ./config_file_puppetserver.markdown
[product.conf]: ./config_file_product.markdown

For release notes on versions of Puppet Server prior to Puppet Server 5, see [docs.puppet.com](https://docs.puppet.com/puppetserver/2.8/release_notes.html).

## Puppet Server 5.3.16

Released 26 October 2020

### Resolved Issues

- The `puppet-ca/v1/clean` endpoint now logs the certname of each certificate it revokes. [SERVER-2897](https://tickets.puppetlabs.com/browse/SERVER-2897)

## Puppet Server 5.3.15

Released 20 October 2020

### New feature

- Adds a new CA API endpoint - `puppet-ca/v1/clean` - that accepts a list of cert names to be revoked and deleted as a batch. [SERVER-2859](https://tickets.puppetlabs.com/browse/SERVER-2859)

### Resolved issues

- The Puppet Server CA will now write all of its files atomically, preventing an issue where CRLs could be read partway through being written, resulting in a failed load and corrupting CA state. [SERVER-2863](https://tickets.puppetlabs.com/browse/SERVER-2863)
- Re-enabled the ability to delete certificate signing requests via the CA API. [SERVER-2795](https://tickets.puppetlabs.com/browse/SERVER-2795)

## Puppet Server 5.3.14

Released 14 July 2020

### Deprecation

- The v1 metrics endpoint, which was recently disabled by default, is now deprecated. Instead, use the v2 endpoint. [TK-486](https://tickets.puppetlabs.com/browse/TK-486)

## Puppet Server 5.3.13

Released 30 April 2020

### New features

- Backports updates to the `puppetserver ca` CLI tool. These changes will be ignored when working with a 5.x CA, but may help with some ugrade scenarios. [SERVER-2591](https://tickets.puppetlabs.com/browse/SERVER-2591), [PUP-10364](https://tickets.puppetlabs.com/browse/PUP-10364)

## Puppet Server 5.3.12

Released 10 March 2020

### Resolved issue 

- To prevent information exposure as a result of [CVE-2020-7943](https://puppet.com/security/cve/CVE-2020-7943), the `/metrics/v1` endpoints are disabled by default, and access to the `/metrics/v2` endpoints are restricted to localhost.

## Puppet Server 5.3.11

Released 14 January 2020

### New features

- When requesting that a certificate be signed, the `certificate-status` API endpoint can now accept a TTL in its body under the key `cert_ttl`, which determines the validity period of the cert being signed. The unit defaults to seconds, but you can specify the unit. See [configuration](https://puppet.com/docs/puppet/latest/configuration.html#configuration-settings) for a list of Puppet's accepted time unit markers. [SERVER-2678](https://tickets.puppetlabs.com/browse/SERVER-2678)

### Resolved issues

- Puppet Server no longer issues HTTP 503 responses to agents older than Puppet 5.3, which can't react to these responses. This allows the `max-queued-requests` setting to be used safely with older agents. [SERVER-2405](https://tickets.puppetlabs.com/browse/SERVER-2405)

## Puppet Server 5.3.10

Released 15 October 2019

### New features

- Puppet Server's CA API now synchronizes write access to the CRL, so that each revoke request updates the CRL in succession, instead of concurrently. This prevents corruption of the CRL due to competing requests.

    This does _not_ affect the `puppet cert` command. If you use `puppet cert revoke` at the same time as a revocation request via the API, the CRL is updated simultaneously and could be corrupted.

    To minimize this risk, use the `puppetserver ca` command line tool -- which uses the CA API -- whenever possible. [SERVER-2641](https://tickets.puppetlabs.com/browse/SERVER-2641)

### Bug fixes

- The `puppetserver ca import` command now initializes an empty CRL for the intermediate CA if one is not provided in the `crl-chain` file. [SERVER-2522](https://tickets.puppetlabs.com/browse/SERVER-2552)

- You can now specify a `--certname` flag with the `puppetserver ca list` command, which will limit the output to information about the requested cert, and log an error if the requested cert does not exist in any form. [SERVER-2589](https://tickets.puppetlabs.com/browse/SERVER-2589)

- Timing metrics associated with borrowing a JRuby instance now include why that JRuby instance was borrowed and access logs now include the time spent in JRuby. [SERVER-1975](https://tickets.puppetlabs.com/browse/SERVER-1975) & [SERVER-2198](https://tickets.puppetlabs.com/browse/SERVER-2198) respectively.

## Puppet Server 5.3.9

Released 16 July 2019

### Bug fixes

- In this release, performance in puppetserver commands is improved. Running `puppetserver gem`, `puppetserver irb`, and other Puppet Server CLI commands are 15-30 percent faster to start up. Service starting and reloading should see similar improvements, along with some marginal improvements to top-end performance, especially in environments with limited sources of entropy.

- Building Puppet Server outside our network is now slightly easier.

- Prior to this release, an unnecessary and deprecated version of Facter was shipped in the `puppetserver` package. This has been removed.

## Puppet Server 5.3.8

Released 26 March 2019

### Bug fixes

- Updated bouncy-castle to 1.60 to fix security issues. [SERVER-2431](https://tickets.puppetlabs.com/browse/SERVER-2431)

## Puppet Server 5.3.7

Released 15 January 2019.

This release contains new features.

### New Features

- The `puppetserver ca` tool now respects the `server_list` setting in `puppet.conf` for those users that have created their own high availability configuration using that feature. [SERVER-2392](https://tickets.puppetlabs.com/browse/SERVER-2392) 
- The EZBake configs now allow you to specify `JAVA_ARGS_CLI`, which is used when using `puppetserver` subcommands to configure Java differently from what is needed for the service. This was used by the CLI before, but as an environment variable only, not as an EZBake config option. [SERVER-2399](https://tickets.puppetlabs.com/browse/SERVER-2399)

### Removals 

- The developer dashboard has been removed.

## Puppet Server 5.3.6

Released 23 October 2018.

This release contains new features.

### New Features

- We have added two settings to Puppet Server's CA configuration: `allow-subject-alt-names` and `allow-autorization-extensions`. These are false by default. When set to true, they allow CSR with subject alt names or special auth extensions to be signed by the Puppet Server CA API. These flags are needed to sign such certs via `puppetserver ca sign` command, which replaces `puppet cert` in Puppet 6. [SERVER-2322](https://tickets.puppetlabs.com/browse/SERVER-2322) 
- The CA service and the CA proxy service (in PE) now have their own entries in the status endpoint output and can be queried as "ca" and "ca-proxy" respectively. [SERVER-2350](https://tickets.puppetlabs.com/browse/SERVER-2350)


## Puppet Server 5.3.5

Released 21 August, 2018

### New features

- We have added a new command line tool for interacting with the Puppet CA, under the `puppetserver ca` command. This tool can be used to generate an intermediate CA for Puppet Server, and to generate, sign, revoke, clean, and list certs. The Puppet 5 series still contains all of the old caveats about using an intermediate CA, including the need to manually copy the certs to the agent, and the need to configure CRL checking. See [Puppet Server: Intermediate CA Configuration](/puppetserver/5.3/intermediate_ca_configuration.html) for details. Therefore the `generate` and `import` commands that create intermediate CAs should be used with caution. All of these actions are executed by making requests to Puppet Server's CA API, in particular the `certificate_status` and `certificate_statuses` endpoints. 

    Currently, requests to these endpoints are denied by the blanket rule in `auth.conf`, so if you would like to try out the new tool, you should first add two rules to `auth.conf` whitelisting your master's certname to talk to those two endpoints. 

    We plan to remove the `puppet cert` command and other assorted CA-related puppet subcommands in Puppet 6; we encourage you to try out this tool now and give us feedback on any bugs or functionality gaps, so we can fix them before removing the tools is it replacing. ([SERVER-2284](https://tickets.puppetlabs.com/browse/SERVER-2284))

- The `puppetserver ca` CLI now has an `import` subcommand for installing key and certificate files generated by the user (for example when they have an external root CA that they need puppetserver's PKI to chain to). ([SERVER-2261](https://tickets.puppetlabs.com/browse/SERVER-2261))

## Puppet Server 5.3.4

Released July 17, 2018.

This is a platform support release of Puppet Server.

-   [All issues resolved in Puppet Server 5.3.4](https://tickets.puppetlabs.com/issues/?jql=fixVersion%20%3D%20%27SERVER%205.3.4%27)

### New platforms

- This release adds Puppet Server packages for Ubuntu 18.04 (Bionic Beaver).

## Puppet Server 5.3.3

Released June 7, 2018.

This is a bug-fix release of Puppet Server. There was no public release of Puppet Server 5.3.2.

-   [All issues resolved in Puppet Server 5.3.3](https://tickets.puppetlabs.com/issues/?jql=fixVersion%20%3D%20%27SERVER%205.3.3%27)

### Bug fixes

-   In Puppet Server 5.3.3, the `/puppet/v3/tasks` endpoint reuses cached environments instead of always creating a new environment. ([SERVER-2192](https://tickets.puppetlabs.com/browse/SERVER-2192))

## Puppet Server 5.3.1

Released April 17, 2018.

This is a feature release of Puppet Server.

### New features

-   We have updated the docs on enabling JRuby 9k to reflect the results of our performance research. Specifically, when using JRuby 9k, set the JVM's code cache to 512MB and enable JIT compilation. ([SERVER-2147](https://tickets.puppetlabs.com/browse/SERVER-2147))

### Known issues

-   YARD's rubygem integration is incompatible with the rubygems version we ship with JRuby 9k. It prints a warning during `puppetserver gem list` (called during Puppet runs applying updates via the `puppetserver_gem` provider). This is caused by using autosign 0.1.2. Upgrading to autosign 0.1.3 bumps the YARD requirement to a version compatible with the rubygems we ship in JRuby 9k-based puppetserver.

    To resolve this issue, upgrade YARD to a 0.9.x version and look at bumping any server side gems that require YARD 0.8.x. The autosign gem specifically should be at >= 0.1.3. ([SERVER-2161](https://tickets.puppetlabs.com/browse/SERVER-2161))

## Puppet Server 5.3.0

Released March 20, 2018.

This is a feature and bug-fix release of Puppet Server.

### Bug fixes

-   Puppet Server 5.3.0 compresses catalog response bodies with gzip when requested. Since previous versions of Puppet Server also gzipped all other response bodies, Puppet Server 5.3.0 can  gzip response bodies for _all_ POST requests.

### New features

-   When using JRuby 9k, Puppet Server 5.3.0 defaults to using a JRuby compile mode of "JIT", which provides the best performance.

-   Puppet Server 5.3.0 can use some gems shipped by `puppet-agent`. The new shared directory is `/opt/puppetlabs/puppet/lib/ruby/vendor_gems`.

## Puppet Server 5.2.0

Released February 13, 2018.

This is a feature and bug-fix release of Puppet Server.

### Bug fixes

-   Previous versions of Puppet Server assigned the same `max-requests-per-instance` interval to all JRuby worker instances, and when equally sharing the request load, all of the instances would be destroyed and recreated near the same time. This created a "thundering heard" problem with JRuby instance creation that could lead to a spike in request times.

    Puppet Server 5.2.0 attempts to splay the destruction and recreation of JRuby instances equally over the `max-request-per-instance` interval to avoid this issue. This changes the operability metrics of the server by removing large spikes in request times in exchange for smaller but more frequent slowdowns.

-   Puppet Server 5.2.0 is much less prone than previous versions to a race condition where systemd could lose track of the `puppetserver` process.

-   In Puppet Server 2.3.x and later, using shell redirection and other shell features in custom functions would result in failures because the command would not be run in a shell. Puppet Server 5.2.0 runs these commands in a shell.

### New features

-   Profiling at the JRuby level can be enabled in the `jruby-puppet` section of `puppetserver.conf`. This can be used to profile all of Puppet, including custom Ruby code. Due to the amount of output, enabling profiling will degrade performance and should not be done in production.

    There are two new settings for this feature: `profiling-mode` and `profiling-output-file`. For details, see the [puppetserver.conf documentation](./config_file_puppetserver.markdown).

    The API profiling mode can be used to [profile custom user code](https://github.com/jruby/jruby/wiki/Profiling-JRuby#profiling-specific-code-in-an-application), and there are [resources for profiling JRuby code](https://github.com/jruby/jruby/wiki/Profiling-JRuby).

-   The `hiera-eyaml` gem is installed with Puppet Server by default, enabling out-of-the-box use of this encrypted Hiera backend.

-   The `status` endpoint in Puppet Server 5.2.0 includes metrics on time spent waiting for an ENC response, as well as all major PDB events (submitting, querying, and transforming data). It also reports the number of times Puppet Server has reached its `max-queued-requests` limit, which provides more insight into when, and how much, their request load is exceeding capacity.

## Puppet Server 5.1.5

Released January 31, 2018.

This is a minor bug-fix release of Puppet Server, and adds packages for Debian 9 ("Stretch").

### Bug fixes

-   Puppet Server correctly parses URIs with spaces in them, thanks to a bug fix in the `bidi` dependency.

-   Puppet Server parses all Ruby source files as UTF-8 instead of ASCII when running under JRuby 1.7. This is the same behavior as MRI Ruby and JRuby 9K, and avoids corner-case bugs when interpolating translated strings with Unicode characters.

## Puppet Server 5.1.4

Released November 6, 2017.

This is a minor feature release of Puppet Server.

### New feature: Serve versioned tasks from static file content endpoint

Tasks available from versioned code will be served via the static file content endpoint, rather than out of Puppet's file serving. This should reduce resources used to serve tasks.

-   [SERVER-1993](https://tickets.puppetlabs.com/browse/SERVER-1993)

## Puppet Server 5.1.3

Released October 2, 2017.

This is a bug-fix release of Puppet Server. Puppet Server 5.1.1 and 5.1.2 were not packaged for release.

### Bug fix: Change logging level selection to improve performance

Previous versions of Puppet Server set the default logging level to `debug`, then filtered the log output using Logback. Because Puppet generates a very large amount of output in debug mode, this behavior could significantly degrade Puppet Server's performance. Server 5.1.3 resolves this issue by producing `debug` output only when configured to do so. For details about setting logging levels, see [the logback.xml configuration documentation](./config_file_logbackxml.markdown).

-   [SERVER-1922](https://tickets.puppetlabs.com/browse/SERVER-1922)

### Packaging change: Use operating system codename in Debian packages' release field

In Debian and Debian-derivative packages from Puppet Server 5.1.3 onward, the release field changes from "1puppetlabs" to "1<OS CODENAME>". For example, the `puppetserver` package version for Server 5.1.3 on Ubuntu 16.04 (Xenial Xerus) is "5.1.3-1xenial", whereas the package version for Server 5.1.0 was "5.1.0-1puppetlabs1".

This fixes an issue where some repository mirroring tools failed to mirror our repositories because packages with different contents had the same name. This does not otherwise affect the package installation process.

-   [CPR-292](https://tickets.puppetlabs.com/browse/CPR-292)
-   [CPR-429](https://tickets.puppetlabs.com/browse/CPR-429)

## Puppet Server 5.1.0

Released September 13, 2017.

This is a feature and bug-fix release of Puppet Server.

### New feature: Puppet agents retry requests on a configurable delay if Puppet Server is busy

When a group of Puppet agents start their Puppet runs together, they can form a "thundering herd" capable of exceeding Puppet Server's available resources. This results in a growing backlog of requests from Puppet agents waiting for a JRuby instance to become free before their request can be processed. If this backlog exceeds the size of the Server's Jetty thread pool, other requests (such as status checks) start timing out. (For more information about JRubies and Server performance, see [Applying metrics to improve performance](./puppet_server_metrics_performance.html#measuring-capacity-with-jrubies).)

In previous versions of Puppet Server, administrators had to manually remediate this situation by separating groups of agent requests, for instance through rolling restarts. In Server 5.1.0, administrators can optionally have Server return a 503 response containing a `Retry-After` header to requests when the JRuby backlog exceeds a certain limit, causing agents to pause before retrying the request.

Both the backlog limit and `Retry-After` period are configurable, as the `max-queued-requests` and `max-retry-delay` settings respectively under the `jruby-puppet` configuration in [puppetserver.conf][]. Both settings' default values do not change Puppet Server's behavior compared to Server 5.0.0, so to take advantage of this feature in Puppet Server 5.1.0, you must specify your own values for `max-queued-requests` and `max-retry-delay`. For details, see the [puppetserver.conf][] documentation. Also, Puppet agents must run Puppet 5.3.0 or newer to respect such headers.

-   [PUP-7451](https://tickets.puppetlabs.com/browse/PUP-7451)
-   [SERVER-1767](https://tickets.puppetlabs.com/browse/SERVER-1767)

### New Feature: Automatic CRL refresh on certificate revocation

Puppet Server 5.1.0 includes the ability to automatically refresh the certificate revocation list (CRL) when any changes to that file have occurred, namely the addition of a revoked certificate. Prior to this release, revoking an agent's certificate required [restarting or reloading](./restarting.markdown) the Puppet Server process before that revocation would be honored and the agent denied authentication. Revocation is now effective within milliseconds and does not require restarting server.

-   [SERVER-1933](https://tickets.puppetlabs.com/browse/SERVER-1933) / [TK-451](https://tickets.puppetlabs.com/browse/TK-451)

### New Feature: Autosigning supports CA certificate bundles

Previous version of Puppet Server did not support autosigning with certificate authority (CA) certificate bundles, which contain multiple certificates. When attempting to pass a bundle, Server would output an error indicating that "the PEM stream must contain exactly 1 certificate". Puppet Server 5.1.0 adds support for autosigning with CA certificate bundles.

-   [SERVER-1315](https://tickets.puppetlabs.com/browse/SERVER-1315)

### New Feature: Adminstrators can add Java JARs to be loaded on startup

In previous versions of Puppet Server, there was no designed way to add Java JARs to be loaded by Puppet Server on startup, for instance to provide native extensions required by certain gems. Server 5.1.0 adds a new directory, `/opt/puppetlabs/server/data/puppetserver/jars`, and loads any JARs placed in this directory to the `classpath` when `puppetserver` is started. JARs placed here will not be modified or removed when upgrading Puppet Server.

-   [SERVER-249](https://tickets.puppetlabs.com/browse/SERVER-249)

### Bug fixes

-   [SERVER-1755](https://tickets.puppetlabs.com/browse/SERVER-1755): Previous versions of Puppet Server did not correctly handle Puppet agent data sent in Msgpack format (`application/x-msgpack`), because Server interpreted the binary data as UTF-8 content. Server 5.1.0 resolves this issue by passing Msgpack content along as raw binary data, just as it came in.

## Puppet Server 5.0.0

Released June 27, 2017.

This is a major release of Puppet Server, and corresponds with the major release of Puppet 5.0, which also includes many changes and new features relevant to Puppet Server users.

### Platform changes

Puppet Server 5.0 packages are available for RHEL 6 and 7, Debian 8 (Jessie), Ubuntu 16.04 (Xenial), and SLES 12 (SP1 or later only).

Puppet Server 5.0 is built with JDK 8, and therefore cannot run on a Java 7 runtime. Server 5.0 packages now depend exclusively upon the `openjdk-8-jre-headless` package. Because Ubuntu 12.04 (Precise) and 14.04 (Trusty), and versions of Debian prior to 8 (Jessie), do not distribute that package, we no longer provide Puppet Server packages for those operating systems.

For Debian 8, install the `jessie-backports` repository to add access to Java 8. For details, see [Installing From Packages](./install_from_packages.markdown).

-   [SERVER-1738](https://tickets.puppetlabs.com/browse/SERVER-1738)
-   [SERVER-1741](https://tickets.puppetlabs.com/browse/SERVER-1741)
-   [SERVER-1800](https://tickets.puppetlabs.com/browse/SERVER-1800)

### Upgrading from previous versions

Consult the [Puppet 5.0 release notes](https://puppet.com/docs/puppet/5.0/release_notes.html) for more information about important new features and breaking changes. Some especially relevant Puppet 5.0 changes are also noted below.

#### Deprecation: Puppet 5 no longer writes YAML node caches

As of Puppet 5.0, Puppet no longer writes node YAML files to its cache by default. This cache has been used in workflows where external tooling needs a list of nodes, but PuppetDB is now the preferred source of node information.

To retain the Puppet 4.x behavior, add the [`puppet.conf`](./configuration.markdown) setting `node_cache_terminus = write_only_yaml`. Note that `write_only_yaml` is deprecated, and users are encouraged to migrate to PuppetDB in order to retrieve node information.

-   [SERVER-1819](https://tickets.puppetlabs.com/browse/SERVER-1819)
-   [PUP-6060](https://tickets.puppetlabs.com/browse/PUP-6060)

#### Deprecation/New Feature: Puppet 5 uses JSON serialization by default

Previous versions of Puppet exclusively used PSON, our vendored version of `pure_json`, for serializing communication between agents and masters. Testing showed that PSON serialization's performance was significantly worse than using JSON, and JSON adds opportunities for easier and better interoperability with other tools and programming languages.

Puppet Server 5.0 now produces `application/json` responses when a Puppet 5.x agent requests them, and consumes `application/json` request bodies sent from Puppet 5.x agents.

Server 5.0 remains compatible with Puppet 3.x and 4.x agents that use PSON, and Puppet 5.x agents attempt to request and send `text/pson` only when communicating with masters that don't support JSON communications, such as Puppet Server 2.7.x or earlier.

For details, consult the [Puppet 5.0 documentation](https://puppet.com/docs/puppet/5.0/release_notes.html).

-   [PUP-3852](https://tickets.puppetlabs.com/browse/PUP-3852)

----

### Security Fix: Default `auth.conf` rules clarify what's allowed for `file_` endpoints

Puppet Server 5.0 updates the default authorization rules in the [`auth.conf`][auth.conf] file to reflect that access to the "delete" HTTP method for all `/puppet/v3/file_` endpoints cannot be granted, even if a rule in `auth.conf` would permit it. This access is always forbidden due to a hard-coded restriction in the Ruby Puppet endpoint code.

For clarity, the `file_` endpoint rules are now separated into definitions per endpoint in order to reflect the valid methods that each endpoint supports, including `file_bucket_file`, `file_content`, and `file_metadata`.

-   [SERVER-1808](https://tickets.puppetlabs.com/browse/SERVER-1808)

### Deprecation: Puppet Server 5 no longer runs on JDK 7

### Deprecation: Legacy `auth.conf` settings are no longer enabled by default

By default, Puppet Server uses the HOCON-based [`auth.conf`][auth.conf] file introduced in Puppet Server 2.4. This uses the `trapperkeeper-authorization` methods of evaluating access to HTTP endpoints, instead of the legacy Puppet `auth.conf` file.

Puppet Server 5.0 completes this switch by changing the default value of the `use-legacy-auth-conf` setting in [`puppetserver.conf`](./config_file_puppetserver.markdown) from true to false.

-   [SERVER-1732](https://tickets.puppetlabs.com/browse/SERVER-1732)

### Deprecation: Removed `resource_types` API endpoints and `resource_type` CLI face

Puppet Server 5.0 removes the deprecated HTTP `resource_type` and `resource_types` API endpoints, and the `resource_type` Puppet CLI face. The [`/puppet/v3/environment_classes`](./puppet-api/v3/environment_classes.markdown) HTTP endpoint in Puppet Server replaces a subset of the `resource_type` functionality, including name and parameter metadata for classes.

-   [SERVER-1251](https://tickets.puppetlabs.com/browse/SERVER-1251)
-   [SERVER-1120](https://tickets.puppetlabs.com/browse/SERVER-1120)

### New Feature: Performance metrics

Puppet Server 5.0 includes metrics support previously released in Puppet Enterprise, including [Grafana and Graphite support](./puppet_server_metrics.markdown), both [PE-style](./metrics-api/v1/metrics_api.markdown) and [Jolokia-powered](./metrics-api/v2/metrics_api.markdown) metrics API endpoints, and the developer dashboard.

-   [SERVER-1797](https://tickets.puppetlabs.com/browse/SERVER-1797)
-   [SERVER-1752](https://tickets.puppetlabs.com/browse/SERVER-1752)
-   [SERVER-1739](https://tickets.puppetlabs.com/browse/SERVER-1739)
-   [SERVER-1737](https://tickets.puppetlabs.com/browse/SERVER-1737)
-   [SERVER-1721](https://tickets.puppetlabs.com/browse/SERVER-1721)
-   [SERVER-1266](https://tickets.puppetlabs.com/browse/SERVER-1266)
-   [SERVER-1262](https://tickets.puppetlabs.com/browse/SERVER-1262)
-   [SERVER-1261](https://tickets.puppetlabs.com/browse/SERVER-1261)
-   [SERVER-1260](https://tickets.puppetlabs.com/browse/SERVER-1260)
-   [SERVER-1259](https://tickets.puppetlabs.com/browse/SERVER-1259)

### New Feature: Optional support for JRuby 9k

Puppet Server 5.0 packages include the dependencies for both JRuby 1.7 (running Ruby language version 1.9.3) and JRuby 9k (running Ruby language version 2.3 or later). Puppet Server 5.0 uses JRuby 1.7 by default.

To instead run Puppet Server using JRuby 9k, see the "Configuring the JRuby Version" section of [Puppet Server Configuration](./configuration.markdown).

To facilitate this, the Puppet Server packages include both JRuby 1.7.27 and JRuby 9k, increasing package sizes by about 30 MB.

-   [SERVER-1630](https://tickets.puppetlabs.com/browse/SERVER-1630)

### Deprecation: `jruby-puppet.compat-version` setting removed

Puppet Server 5.0 also updates JRuby v1.7 to v1.7.27, which in turn updates the `jruby-openssl` gem to v0.9.19 and `bouncycastle` libraries to v1.55. JRuby 1.7.27 breaks setting `jruby-puppet.compat-version` to `2.0` in [`puppetserver.conf`](./config_file_puppetserver.markdown), so Server 5.0 removes the `jruby-puppet.compat-version` setting and exits the `puppetserver` service with an error if you start the service with that setting.

For Ruby language 2.x support in Puppet Server, configure Puppet Server to use JRuby 9k instead of JRuby 1.7.27 as noted above.

-   [SERVER-1630](https://tickets.puppetlabs.com/browse/SERVER-1630)

### New Feature: Logback logging for JRuby

Previous versions of Puppet Server logged JRuby errors to stderr, which Puppet Server wrote to either `/var/log/puppetlabs/puppetserver/puppetserver-daemon.log`, `syslog`, or `journalctl` (depending upon the OS). By default, JRuby also does not log debug messages.

To take advantage of the [Logback](./configuration.markdown#Logback) logging infrastructure already in Puppet Server, JRuby now uses a custom `slf4j` logger that bridges logging from JRuby to Logback. As a result, Puppet Server now logs "info" and "error" messages from JRuby into `/var/log/puppetlabs/puppetserver/puppetserver.log` by default. To log debug-level JRuby messages, set the "level" attribute for the "jruby" element in [`/etc/puppetlabs/puppetserver/logback.xml`](./config_file_logbackxml.markdown) file to "debug".

-   [SERVER-1475](https://tickets.puppetlabs.com/browse/SERVER-1475)

### New Feature: Profiling enabled by default

Puppet Server 5.0 enables [profiling](.config_file_puppetserver.markdown) by default, matching the default behavior of Puppet Server in Puppet Enterprise.

-   [SERVER-1761](https://tickets.puppetlabs.com/browse/SERVER-1761)

### New Feature: `environment_modules` endpoint can return all modules in all environments

Puppet Server 5.0 makes the environment query parameter of the [`puppet/v3/environment_modules`](./puppet-api/v3/environment_modules.markdown) endpoint optional. Also, to retrieve information about all modules in all environments at once, make a GET request of the `puppet/v3/environment_modules` endpoint without passing any parameters.

-   [SERVER-1758](https://tickets.puppetlabs.com/browse/SERVER-1758)

### New Feature: Respect HTTP(S) proxy variables for `puppetserver gem` subcommand

In Puppet Server 5.0, the `puppetserver gem` command respects `HTTP_PROXY`, `http_proxy`, `HTTPS_PROXY`, `https_proxy`, `NO_PROXY`, and `no_proxy` environment variables, allowing `puppetserver` to install gems when behind a proxy defined by these environment variables.

-   [SERVER-377](https://tickets.puppetlabs.com/browse/SERVER-377)

### Bug Fix: Add timeout to JRuby lock requests to avoid hanging the `puppetserver` service

In previous versions of Puppet Server, a request to lock all JRuby instances would stall indefinitely was made while a single instance in the JRuby pool stalled, effectively deadlocking the `puppetserver` service and requiring manual intervention to release the lock.

Puppet Server 5.0 resolves this by adding a timeout to the pool lock request. If that timeout expires, Puppet Server throws an exception instead of locking up indefinitely.

-    [SERVER-1704](https://tickets.puppetlabs.com/browse/SERVER-1704)

### Bug Fix: Include Content-Type headers in static file content API responses

In previous versions of Puppet Server, responses to requests of the `puppet/v3/static_file_content` endpoint did not include a Content-Type header. As of Puppet Server 5.0, responses to successful requests include a `application/octet-stream` Content-Type header. Error responses from this endpoint include a `text/plain` Content-Type.

-   [SERVER-1826](https://tickets.puppetlabs.com/browse/SERVER-1826)

### Bug Fix: Require `which` command for RPM package installation

Installing previous versions of Puppet Server via RPM packages could fail with a `which: command not found` error if `which` was not installed on the system.

Puppet Server 5.0 packages require `/usr/bin/which`, ensuring that it will be installed.

-   [SERVER-1746](https://tickets.puppetlabs.com/browse/SERVER-1746)

### Bug Fix: Wait for `puppetserver` to stop before attempting to restart it via sysvinit

In previous versions of Puppet Server, attempting to restart the `puppetserver` service using its sysvinit script would always try to immediately restart the service, even if stopping the service failed. Also, in some cases the service might not terminate immediately when sent a kill signal (SIGKILL), which exacerbated the issue.

Puppet Server 5.0 resolves this by waiting for a grace period after sending a SIGKILL to the `puppetserver` service to ensure that it successfully exits, and attempts to restart the service only after the service is successfully stopped.

### Bug Fix: Improved handling of CLI subcommands

In previous versions of Puppet Server, `puppetserver` CLI subcommands discarded exit codes, which could prevent errors from commands like `puppetserver gem` from being detected. In Puppet Server 5.0, these CLI commands now exit with the same return code as the Ruby command would have provided.

-   [SERVER-1759](https://tickets.puppetlabs.com/browse/SERVER-1759)

Also, subcommands previously did not account for errors raised when loading and validating the `puppetserver` configuration. An invalid configuration could crash the subcommand with an unhelpful error message unrelated to the actual configuration validation failure. Puppet Server 5.0 resolves this by propagating errors with an unexpected format to output without modification.

-   [SERVER-1690](https://tickets.puppetlabs.com/browse/SERVER-1690)

In previous versions of Puppet Server, `puppetserver` CLI subcommands used `/dev/random` for entropy. On systems with limited sources of entropy, such as virtual machines, these subcommands could rapidly drain the entropy pool, leading to slow performance as the pool gradually refilled. For instance, this could cause gem installation using the `puppetserver gem` subcommand to take much longer than expected.

Puppet Server 5.0 resolves this by instead using `/dev/urandom` for CLI subcommands.

-   [SERVER-1723](https://tickets.puppetlabs.com/browse/SERVER-1723)

### Other changes

-   [SERVER-1807](https://tickets.puppetlabs.com/browse/SERVER-1807): Puppet Server 5.0 embeds `hocon` gem version 1.2.5 in the default Puppet Server gem path, an upgrade from v1.1.3 used in Server v2.7.2.
-   [SERVER-1671](https://tickets.puppetlabs.com/browse/SERVER-1671): Log only error output (stderr) to `puppetserver.log` for `generate()` function calls.
- [SERVER-715](https://tickets.puppetlabs.com/browse/SERVER-715): Resolve intermittent 500 error status results and `isExpired(puppet_environments.clj:27)` logged errors on some HTTP API requests.
