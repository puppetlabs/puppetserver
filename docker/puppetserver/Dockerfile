ARG version="6.0.0"
ARG namespace="puppet"
FROM "$namespace"/puppetserver-standalone:"$version"

ARG vcs_ref
ARG build_date

# Used by entrypoint to submit metrics to Google Analytics.
# Published images should use "production" for this build_arg.
ARG pupperware_analytics_stream="dev"
ENV PUPPERWARE_ANALYTICS_STREAM="$pupperware_analytics_stream"
ENV PUPPERWARE_ANALYTICS_TRACKING_ID="UA-132486246-3"
ENV PUPPERWARE_ANALYTICS_APP_NAME="puppetserver"
ENV PUPPERWARE_ANALYTICS_ENABLED=false
ENV PUPPET_STORECONFIGS_BACKEND="puppetdb"
ENV PUPPET_STORECONFIGS=true
ENV PUPPET_REPORTS="puppetdb"

LABEL org.label-schema.maintainer="Puppet Release Team <release@puppet.com>" \
      org.label-schema.vendor="Puppet" \
      org.label-schema.url="https://github.com/puppetlabs/puppetserver" \
      org.label-schema.name="Puppet Server" \
      org.label-schema.license="Apache-2.0" \
      org.label-schema.version="$PUPPET_SERVER_VERSION" \
      org.label-schema.vcs-url="https://github.com/puppetlabs/puppetserver" \
      org.label-schema.vcs-ref="$vcs_ref" \
      org.label-schema.build-date="$build_date" \
      org.label-schema.schema-version="1.0" \
      org.label-schema.dockerfile="/Dockerfile"

RUN apt-get update && \
    apt-get install --no-install-recommends -y puppetdb-termini && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

RUN puppet config set storeconfigs_backend puppetdb --section master && \
    puppet config set storeconfigs true --section master && \
    puppet config set reports puppetdb --section master && \
    cp -p /etc/puppetlabs/puppet/puppet.conf /var/tmp/puppet

COPY puppetdb.conf /var/tmp/puppet/

COPY Dockerfile /
