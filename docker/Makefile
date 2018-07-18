git_describe = $(shell git describe)
vcs_ref := $(shell git rev-parse HEAD)
build_date := $(shell date -u +%FT%T)
hadolint_available := $(shell hadolint --help > /dev/null 2>&1; echo $$?)
hadolint_command := hadolint --ignore DL3008 --ignore DL3018 --ignore DL4000 --ignore DL4001
hadolint_container := hadolint/hadolint:latest
makefile_path := $(abspath $(lastword $(MAKEFILE_LIST)))
makefile_dir := $(dir $(makefile_path))
agent_ref = $(shell grep puppet_build_version $(makefile_dir)/../acceptance/config/beaker/options.rb | cut -d\" -f2)

BUILD_TARGETS = build-ubuntu

ifeq ($(IS_NIGHTLY),true)
	dockerfile := Dockerfile.nightly
	version := puppet6-nightly
	BUILD_TARGETS += build-alpine
else
	version = $(shell echo $(git_describe) | sed 's/-.*//')
	dockerfile := Dockerfile
endif

prep:
ifneq ($(IS_NIGHTLY),true)
	@git fetch --unshallow ||:
	@git fetch origin 'refs/tags/*:refs/tags/*'
endif

lint:
ifeq ($(hadolint_available),0)
	$(hadolint_command) puppetserver-standalone/$(dockerfile)
	$(hadolint_command) puppetserver/$(dockerfile)
else
	docker pull $(hadolint_container)
	docker run --rm -v $(PWD)/puppetserver-standalone/$(dockerfile):/Dockerfile -i $(hadolint_container) $(hadolint_command) Dockerfile
	docker run --rm -v $(PWD)/puppetserver/$(dockerfile):/Dockerfile -i $(hadolint_container) $(hadolint_command) Dockerfile
	docker run --rm -v $(PWD)/puppetserver-standalone-alpine/Dockerfile:/Dockerfile -i $(hadolint_container) $(hadolint_command) Dockerfile
endif

puppet-agent/.git/config:
	@git clone https://github.com/puppetlabs/puppet-agent.git puppet-agent

build-alpine-agent: prep puppet-agent/.git/config
	@git -C puppet-agent fetch origin
	@git -C puppet-agent checkout $(agent_ref)
	@docker build -f puppet-agent/docker/puppet-agent-alpine/Dockerfile puppet-agent -t puppet-agent-alpine:$(agent_ref)

build-alpine: prep build-alpine-agent
	@docker build --build-arg vcs_ref=$(vcs_ref) --build-arg agent_ref=$(agent_ref) --build-arg build_date=$(build_date) --build-arg version=$(version) --file puppetserver-standalone-alpine/Dockerfile --tag puppet/puppetserver-standalone-alpine:$(version) $(makefile_dir)/..
ifeq ($(IS_LATEST),true)
	@docker tag puppet/puppetserver-standalone-alpine:$(version) puppet/puppetserver-standalone-alpine:latest
endif

build-ubuntu: prep
	@docker build --pull --build-arg vcs_ref=$(vcs_ref) --build-arg build_date=$(build_date) --build-arg version=$(version) --file puppetserver-standalone/$(dockerfile) --tag puppet/puppetserver-standalone:$(version) puppetserver-standalone
	@docker build --build-arg vcs_ref=$(vcs_ref) --build-arg build_date=$(build_date) --build-arg version=$(version) --file puppetserver/$(dockerfile) --tag puppet/puppetserver:$(version) puppetserver
ifeq ($(IS_LATEST),true)
	@docker tag puppet/puppetserver-standalone:$(version) puppet/puppetserver-standalone:latest
	@docker tag puppet/puppetserver:$(version) puppet/puppetserver:latest
endif

build: $(BUILD_TARGETS)

test: prep
	@bundle install --path .bundle/gems
	@PUPPET_TEST_DOCKER_IMAGE=puppet/puppetserver-standalone:$(version) bundle exec rspec puppetserver-standalone/spec
	@PUPPET_TEST_DOCKER_IMAGE=puppet/puppetserver:$(version) bundle exec rspec puppetserver/spec

publish: prep
	@docker push puppet/puppetserver-standalone:$(version)
	@docker push puppet/puppetserver:$(version)
ifeq ($(IS_LATEST),true)
	@docker push puppet/puppetserver-standalone:latest
	@docker push puppet/puppetserver:latest
endif

.PHONY: lint build test publish
