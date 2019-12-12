ARG version="6.0.0"
ARG namespace="puppet"
FROM ubuntu:18.04 as build

ENV LANG="en_US.UTF-8"
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
            language-pack-en \
            openjdk-8-jdk-headless \
            curl \
            git \
            ruby \
            ruby-dev \
            g++ \
            make && \
    git config --global user.name "Puppet Release Team" && \
    git config --global user.email "release@puppet.com" && \
    curl --output /usr/local/bin/lein https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein && \
    chmod 0755 /usr/local/bin/lein && \
    /usr/local/bin/lein && \
    gem install --no-doc bundler fpm

COPY . /puppetserver
WORKDIR /puppetserver
RUN lein clean && \
    lein install && \
    EZBAKE_ALLOW_UNREPRODUCIBLE_BUILDS=true EZBAKE_NODEPLOY=true COW=base-bionic-amd64.cow MOCK='' GEM_SOURCE=https://rubygems.org lein with-profile ezbake ezbake local-build && \
    mv /puppetserver/output/deb/bionic/*/*.deb /puppetserver.deb

FROM "$namespace"/puppetserver-base:"$version"


LABEL org.label-schema.maintainer="Puppet Release Team <release@puppet.com>" \
      org.label-schema.vendor="Puppet" \
      org.label-schema.url="https://github.com/puppetlabs/puppetserver" \
      org.label-schema.name="Puppet Server (from source)" \
      org.label-schema.license="Apache-2.0" \
      org.label-schema.version="$PUPPET_SERVER_VERSION" \
      org.label-schema.vcs-url="https://github.com/puppetlabs/puppetserver" \
      org.label-schema.vcs-ref="$vcs_ref" \
      org.label-schema.build-date="$build_date" \
      org.label-schema.schema-version="1.0" \
      org.label-schema.dockerfile="/Dockerfile"

COPY --from=build /puppetserver.deb /puppetserver.deb



RUN wget http://nightlies.puppet.com/apt/puppet6-nightly-release-"$UBUNTU_CODENAME".deb && \
    dpkg -i puppet6-nightly-release-"$UBUNTU_CODENAME".deb && \
    rm puppet6-nightly-release-"$UBUNTU_CODENAME".deb && \
    apt-get update && \
    apt-get install --no-install-recommends -y /puppetserver.deb puppetdb-termini && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    gem install --no-rdoc --no-ri r10k && \
    puppet config set autosign true --section master && \
    cp -pr /etc/puppetlabs/puppet /var/tmp && \
    cp -pr /opt/puppetlabs/server/data/puppetserver /var/tmp && \
    rm -rf /var/tmp/puppet/ssl

COPY docker/puppetserver/puppetserver /etc/default/puppetserver
COPY docker/puppetserver/logback.xml \
     docker/puppetserver/request-logging.xml \
     /etc/puppetlabs/puppetserver/
COPY docker/puppetserver/puppetserver.conf /etc/puppetlabs/puppetserver/conf.d/
COPY docker/puppetserver/puppetdb.conf /etc/puppetlabs/puppet/

COPY docker/puppetserver/Dockerfile /
