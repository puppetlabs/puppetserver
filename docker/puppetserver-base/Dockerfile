FROM ubuntu:18.04

ARG vcs_ref
ARG build_date
ARG version="6.0.0"
# Used by entrypoint to submit metrics to Google Analytics.
# Published images should use "production" for this build_arg.
ARG pupperware_analytics_stream="dev"

LABEL org.label-schema.maintainer="Puppet Release Team <release@puppet.com>" \
      org.label-schema.vendor="Puppet" \
      org.label-schema.url="https://github.com/puppetlabs/puppetserver" \
      org.label-schema.name="Puppet Server Base Image" \
      org.label-schema.license="Apache-2.0" \
      org.label-schema.vcs-url="https://github.com/puppetlabs/puppetserver" \
      org.label-schema.schema-version="1.0" \
      org.label-schema.dockerfile="/Dockerfile"

ENV PUPPERWARE_ANALYTICS_TRACKING_ID="UA-132486246-4" \
    PUPPERWARE_ANALYTICS_APP_NAME="puppetserver" \
    PUPPERWARE_ANALYTICS_ENABLED=false \
    DUMB_INIT_VERSION="1.2.1" \
    UBUNTU_CODENAME="bionic" \
    PUPPETSERVER_JAVA_ARGS="-Xms512m -Xmx512m" \
    PATH=/opt/puppetlabs/server/bin:/opt/puppetlabs/puppet/bin:/opt/puppetlabs/bin:$PATH \
    PUPPET_MASTERPORT=8140 \
    PUPPETSERVER_MAX_ACTIVE_INSTANCES=1 \
    PUPPETSERVER_MAX_REQUESTS_PER_INSTANCE=0 \
    CA_ENABLED=true \
    CA_ALLOW_SUBJECT_ALT_NAMES=false \
    CONSUL_ENABLED=false \
    CONSUL_HOSTNAME=consul \
    CONSUL_PORT=8500 \
    NETWORK_INTERFACE=eth0 \
    USE_PUPPETDB=true \
    PUPPET_STORECONFIGS_BACKEND="puppetdb" \
    PUPPET_STORECONFIGS=true \
    PUPPET_REPORTS="puppetdb"

EXPOSE 8140

ENTRYPOINT ["dumb-init", "/docker-entrypoint.sh"]
CMD ["foreground"]

COPY docker-entrypoint.sh healthcheck.sh /
COPY docker-entrypoint.d /docker-entrypoint.d
HEALTHCHECK --interval=10s --timeout=15s --retries=12 --start-period=3m CMD ["/healthcheck.sh"]

# dynamic LABELs and ENV vars placed lower for the sake of Docker layer caching
# these are specific to analytics
ENV PUPPERWARE_ANALYTICS_STREAM="$pupperware_analytics_stream" \
    PUPPET_SERVER_VERSION="$version"

LABEL org.label-schema.version="$version" \
      org.label-schema.vcs-ref="$vcs_ref" \
      org.label-schema.build-date="$build_date"

RUN chmod +x /docker-entrypoint.sh /healthcheck.sh && \
    apt-get update && \
    apt-get install -y --no-install-recommends wget ca-certificates git && \
    wget https://github.com/Yelp/dumb-init/releases/download/v"$DUMB_INIT_VERSION"/dumb-init_"$DUMB_INIT_VERSION"_amd64.deb && \
    dpkg -i dumb-init_"$DUMB_INIT_VERSION"_amd64.deb && \
    rm dumb-init_"$DUMB_INIT_VERSION"_amd64.deb && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

COPY Dockerfile /base.Dockerfile
