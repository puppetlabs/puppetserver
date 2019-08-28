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
RUN lein clean && lein install
RUN EZBAKE_ALLOW_UNREPRODUCIBLE_BUILDS=true EZBAKE_NODEPLOY=true COW=base-bionic-amd64.cow MOCK='' GEM_SOURCE=https://rubygems.org lein with-profile ezbake ezbake local-build
RUN mv /puppetserver/output/deb/bionic/*/*.deb /puppetserver.deb

FROM ubuntu:18.04

ARG vcs_ref
ARG build_date
ARG version="6.0.0"
# Used by entrypoint to submit metrics to Google Analytics.
# Published images should use "production" for this build_arg.
ARG pupperware_analytics_stream="dev"
ENV PUPPERWARE_ANALYTICS_STREAM="$pupperware_analytics_stream"
ENV PUPPERWARE_ANALYTICS_TRACKING_ID="UA-132486246-4"
ENV PUPPERWARE_ANALYTICS_APP_NAME="puppetserver-standalone"
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

LABEL org.label-schema.maintainer="Puppet Release Team <release@puppet.com>" \
      org.label-schema.vendor="Puppet" \
      org.label-schema.url="https://github.com/puppetlabs/puppetserver" \
      org.label-schema.name="Puppet Server (No PuppetDB)" \
      org.label-schema.license="Apache-2.0" \
      org.label-schema.version="$PUPPET_SERVER_VERSION" \
      org.label-schema.vcs-url="https://github.com/puppetlabs/puppetserver" \
      org.label-schema.vcs-ref="$vcs_ref" \
      org.label-schema.build-date="$build_date" \
      org.label-schema.schema-version="1.0" \
      org.label-schema.dockerfile="/Dockerfile"

COPY --from=build /puppetserver.deb /puppetserver.deb

RUN apt-get update && \
    apt-get install -y --no-install-recommends wget ca-certificates && \
    wget http://nightlies.puppet.com/apt/puppet6-nightly-release-"$UBUNTU_CODENAME".deb && \
    wget https://github.com/Yelp/dumb-init/releases/download/v"$DUMB_INIT_VERSION"/dumb-init_"$DUMB_INIT_VERSION"_amd64.deb && \
    dpkg -i puppet6-nightly-release-"$UBUNTU_CODENAME".deb && \
    dpkg -i dumb-init_"$DUMB_INIT_VERSION"_amd64.deb && \
    rm puppet6-nightly-release-"$UBUNTU_CODENAME".deb dumb-init_"$DUMB_INIT_VERSION"_amd64.deb && \
    apt-get update && \
    apt-get install --no-install-recommends -y git && \
    apt-get install --no-install-recommends -y /puppetserver.deb && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    gem install --no-rdoc --no-ri r10k

COPY docker/puppetserver-standalone/puppetserver /etc/default/puppetserver
COPY docker/puppetserver-standalone/logback.xml /etc/puppetlabs/puppetserver/
COPY docker/puppetserver-standalone/request-logging.xml /etc/puppetlabs/puppetserver/
COPY docker/puppetserver-standalone/puppetserver.conf /etc/puppetlabs/puppetserver/conf.d/

RUN puppet config set autosign true --section master && \
    cp -pr /etc/puppetlabs/puppet /var/tmp && \
    rm -rf /var/tmp/puppet/ssl

COPY docker/puppetserver-standalone/docker-entrypoint.sh /
RUN chmod +x /docker-entrypoint.sh
COPY docker/puppetserver-standalone/docker-entrypoint.d /docker-entrypoint.d

EXPOSE 8140

ENTRYPOINT ["dumb-init", "/docker-entrypoint.sh"]
CMD ["foreground"]

COPY docker/puppetserver-standalone/healthcheck.sh /
RUN chmod +x /healthcheck.sh
HEALTHCHECK --interval=5s --timeout=5s --retries=12 --start-period=120s CMD ["/healthcheck.sh"]

COPY docker/puppetserver-standalone/Dockerfile /
