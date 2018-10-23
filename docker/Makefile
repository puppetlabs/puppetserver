git_describe := $(shell git describe)
version := $(shell echo $(git_describe) | sed 's/-.*//')
nightly_version := puppet6-nightly
vcs_ref := $(shell git rev-parse HEAD)
build_date := $(shell date -u +%FT%T)
hadolint_available := $(shell hadolint --help > /dev/null 2>&1; echo $$?)
hadolint_command := hadolint --ignore DL3008 --ignore DL3018 --ignore DL4000 --ignore DL4001
hadolint_container := hadolint/hadolint:latest

prep:
	@git pull --unshallow > /dev/null 2>&1 ||:
	@git fetch origin 'refs/tags/*:refs/tags/*' > /dev/null 2>&1

lint:
ifeq ($(hadolint_available),0)
	@$(hadolint_command) puppetserver-standalone/Dockerfile
	@$(hadolint_command) puppetserver/Dockerfile
else
	@docker pull $(hadolint_container)
	@docker run --rm -v $(PWD)/puppetserver-standalone/Dockerfile:/Dockerfile -i $(hadolint_container) $(hadolint_command) Dockerfile
	@docker run --rm -v $(PWD)/puppetserver/Dockerfile:/Dockerfile -i $(hadolint_container) $(hadolint_command) Dockerfile
endif

lint-nightly:
ifeq ($(hadolint_available),0)
	$(hadolint_command) puppetserver-standalone/Dockerfile.nightly
	$(hadolint_command) puppetserver/Dockerfile.nightly
else
	docker pull $(hadolint_container)
	docker run --rm -v $(PWD)/puppetserver-standalone/Dockerfile.nightly:/Dockerfile -i $(hadolint_container) $(hadolint_command) Dockerfile
	docker run --rm -v $(PWD)/puppetserver/Dockerfile.nightly:/Dockerfile -i $(hadolint_container) $(hadolint_command) Dockerfile
endif

build: prep
	@docker build --build-arg vcs_ref=$(vcs_ref) --build-arg build_date=$(build_date) --build-arg version=$(version) --tag puppet/puppetserver-standalone:$(version) puppetserver-standalone
	@docker build --build-arg vcs_ref=$(vcs_ref) --build-arg build_date=$(build_date) --build-arg version=$(version) --tag puppet/puppetserver:$(version) puppetserver
ifeq ($(IS_LATEST),true)
	@docker tag puppet/puppetserver-standalone:$(version) puppet/puppetserver-standalone:latest
	@docker tag puppet/puppetserver:$(version) puppet/puppetserver:latest
endif

build-nightly:
	@docker build --build-arg vcs_ref=$(vcs_ref) --build-arg build_date=$(build_date) --build-arg version=$(nightly_version) --file Dockerfile.nightly --tag puppet/puppetserver-standalone:$(nightly_version) puppetserver-standalone
	@docker build --build-arg vcs_ref=$(vcs_ref) --build-arg build_date=$(build_date) --build-arg version=$(nightly_version) --file Dockerfile.nightly --tag puppet/puppetserver:$(nightly_version) puppetserver

test: prep
	@PUPPET_TEST_DOCKER_IMAGE=puppet/puppetserver-standalone:$(version) rspec puppetserver-standalone/spec
	@PUPPET_TEST_DOCKER_IMAGE=puppet/puppetserver:$(version) rspec puppetserver/spec

test-nightly:
	@PUPPET_TEST_DOCKER_IMAGE=puppet/puppetserver-standalone:$(nightly-version) rspec puppetserver-standalone/spec
	@PUPPET_TEST_DOCKER_IMAGE=puppet/puppetserver-standalone:$(nightly-version) rspec puppetserver/spec

publish: prep
	@docker push puppet/puppetserver-standalone:$(version)
	@docker push puppet/puppetserver:$(version)
ifeq ($(IS_LATEST),true)
	@docker push puppet/puppetserver-standalone:latest
	@docker push puppet/puppetserver:latest
endif

publish-nightly:
	@docker push puppet/puppetserver-standalone:$(nightly-version)
	@docker push puppet/puppetserver:$(nightly-version)

.PHONY: lint lint-nightly build build-nightly test test-nightly publish publish-nightly
