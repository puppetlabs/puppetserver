ARG version="6.0.0"
ARG namespace="puppet"
FROM "$namespace"/puppetserver-base:"$version"
ARG vcs_ref
ARG build_date
ARG version
ARG namespace

LABEL org.label-schema.maintainer="Puppet Release Team <release@puppet.com>" \
      org.label-schema.vendor="Puppet" \
      org.label-schema.url="https://github.com/puppetlabs/puppetserver" \
      org.label-schema.name="Puppet Server" \
      org.label-schema.license="Apache-2.0" \
      org.label-schema.version="$version" \
      org.label-schema.vcs-url="https://github.com/puppetlabs/puppetserver" \
      org.label-schema.vcs-ref="$vcs_ref" \
      org.label-schema.build-date="$build_date" \
      org.label-schema.schema-version="1.0" \
      org.label-schema.dockerfile="/release.Dockerfile"

RUN wget https://apt.puppetlabs.com/puppet6-release-"$UBUNTU_CODENAME".deb && \
    dpkg -i puppet6-release-"$UBUNTU_CODENAME".deb && \
    rm puppet6-release-"$UBUNTU_CODENAME".deb && \
    apt-get update && \
    apt-get install --no-install-recommends -y puppetserver="$version"-1"$UBUNTU_CODENAME" puppetdb-termini && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    gem install --no-rdoc --no-ri r10k && \
    puppet config set autosign true --section master && \
    cp -pr /etc/puppetlabs/puppet /var/tmp && \
    cp -pr /opt/puppetlabs/server/data/puppetserver /var/tmp && \
    rm -rf /var/tmp/puppet/ssl

COPY puppetserver /etc/default/puppetserver
COPY logback.xml request-logging.xml /etc/puppetlabs/puppetserver/
COPY puppetserver.conf /etc/puppetlabs/puppetserver/conf.d/
COPY puppetdb.conf /var/tmp/puppet/

COPY release.Dockerfile /
