FROM ubuntu:18.04

ARG vcs_ref
ARG build_date
ARG version="6.0.0"
# Used by entrypoint to submit metrics to Google Analytics.
# Published images should use "production" for this build_arg.
ARG pupperware_analytics_stream="dev"
ENV PUPPERWARE_ANALYTICS_STREAM="$pupperware_analytics_stream"
ENV PUPPERWARE_ANALYTICS_TRACKING_ID="UA-132486246-4"
ENV PUPPERWARE_ANALYTICS_APP_NAME="puppetserver"
ENV PUPPERWARE_ANALYTICS_ENABLED=false

ENV PUPPET_SERVER_VERSION="$version"
ENV DUMB_INIT_VERSION="1.2.1"
ENV UBUNTU_CODENAME="bionic"
ENV PUPPETSERVER_JAVA_ARGS="-Xms512m -Xmx512m"
ENV PATH=/opt/puppetlabs/server/bin:/opt/puppetlabs/puppet/bin:/opt/puppetlabs/bin:$PATH
ENV PUPPET_MASTERPORT=8140
ENV PUPPETSERVER_MAX_ACTIVE_INSTANCES=1
ENV PUPPETSERVER_MAX_REQUESTS_PER_INSTANCE=0
ENV CA_ENABLED=true
ENV CA_ALLOW_SUBJECT_ALT_NAMES=false
ENV CONSUL_ENABLED=false
ENV CONSUL_HOSTNAME=consul
ENV CONSUL_PORT=8500
ENV NETWORK_INTERFACE=eth0
ENV PUPPET_STORECONFIGS_BACKEND="puppetdb"
ENV PUPPET_STORECONFIGS=true
ENV PUPPET_REPORTS="puppetdb"

LABEL org.label-schema.maintainer="Puppet Release Team <release@puppet.com>" \
      org.label-schema.vendor="Puppet" \
      org.label-schema.url="https://github.com/puppetlabs/puppetserver" \
      org.label-schema.name="Puppet Server Base Image" \
      org.label-schema.license="Apache-2.0" \
      org.label-schema.version="$PUPPET_SERVER_VERSION" \
      org.label-schema.vcs-url="https://github.com/puppetlabs/puppetserver" \
      org.label-schema.vcs-ref="$vcs_ref" \
      org.label-schema.build-date="$build_date" \
      org.label-schema.schema-version="1.0" \
      org.label-schema.dockerfile="/Dockerfile"

RUN apt-get update && \
    apt-get install -y --no-install-recommends wget ca-certificates git && \
    wget https://github.com/Yelp/dumb-init/releases/download/v"$DUMB_INIT_VERSION"/dumb-init_"$DUMB_INIT_VERSION"_amd64.deb && \
    dpkg -i dumb-init_"$DUMB_INIT_VERSION"_amd64.deb && \
    rm dumb-init_"$DUMB_INIT_VERSION"_amd64.deb && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

COPY docker-entrypoint.sh /
RUN chmod +x /docker-entrypoint.sh
COPY docker-entrypoint.d /docker-entrypoint.d

EXPOSE 8140

ENTRYPOINT ["dumb-init", "/docker-entrypoint.sh"]
CMD ["foreground"]

COPY healthcheck.sh /
RUN chmod +x /healthcheck.sh
HEALTHCHECK --interval=10s --timeout=15s --retries=12 --start-period=3m CMD ["/healthcheck.sh"]

COPY Dockerfile /
