ARG agent_ref

FROM openjdk:8-jdk-alpine as lein

ENV LEIN_ROOT true
ENV PATH /usr/local/bin:$PATH

RUN apk add --no-cache bash && \
    mkdir -p /usr/local/bin && \
    wget -O /usr/local/bin/lein https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein && \
    chmod +x /usr/local/bin/lein && \
    lein

FROM lein as build
WORKDIR /usr/src/app

RUN apk add --no-cache make && \
    apk add --no-cache java-jffi-native libc6-compat shadow

COPY project.clj /usr/src/app/

RUN lein deps

COPY . /usr/src/app

# hadolint ignore=SC2046
RUN lein gem install --install-dir /opt/puppetlabs/server/data/puppetserver/vendored-jruby-gems \
    --no-ri --no-rdoc $(sed 's/ /:/' resources/ext/build-scripts/jruby-gem-list.txt)

RUN lein uberjar

FROM puppet-agent-alpine:${agent_ref} as agent

FROM openjdk:8-jre-alpine

ARG vcs_ref
ARG build_date
ARG version="6.0.0"
ENV PUPPETSERVER_JAVA_ARGS="-Xms512m -Xmx512m"

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

RUN apk add --no-cache \
    curl \
    shadow \
    yaml-cpp \
    boost \
    boost-program_options \
    boost-system \
    boost-filesystem \
    boost-regex \
    boost-thread \
    java-jffi-native \
    libc6-compat

COPY docker/conf.d /etc/puppetlabs/puppetserver/conf.d
COPY ezbake/config/services.d /etc/puppetlabs/puppetserver/services.d
COPY ezbake/system-config/services.d/bootstrap.cfg /etc/puppetlabs/puppetserver/bootstrap.cfg
COPY docker/puppetserver-standalone/logback.xml /etc/puppetlabs/puppetserver/
COPY docker/puppetserver-standalone/request-logging.xml /etc/puppetlabs/puppetserver/

RUN mkdir -p /var/run/puppetlabs/puppetserver /var/log/puppetlabs/puppetserver

COPY --from=build /opt/puppetlabs/server/data/puppetserver/vendored-jruby-gems /opt/puppetlabs/server/data/puppetserver/vendored-jruby-gems
COPY --from=build /usr/src/app/target/puppet-server-release.jar /

COPY --from=agent /usr/lib/ruby/vendor_ruby /usr/lib/ruby/vendor_ruby
COPY --from=agent /etc/puppetlabs /etc/puppetlabs
COPY --from=agent /usr/local/bin /usr/local/bin
COPY --from=agent /usr/local/lib/site_ruby /usr/local/lib/site_ruby
COPY --from=agent /usr/local/lib/libfacter.so.* /usr/local/lib/
COPY --from=agent /usr/local/share /usr/local/share
RUN ln -s /usr/local/lib/libfacter.so.* /usr/local/lib/libfacter.so

EXPOSE 8140

CMD ["java", "-cp", "/puppet-server-release.jar", "clojure.main", "-m", "puppetlabs.trapperkeeper.main", "-b", "/etc/puppetlabs/puppetserver/bootstrap.cfg,/etc/puppetlabs/puppetserver/services.d", "-c", "/etc/puppetlabs/puppetserver/conf.d"]
