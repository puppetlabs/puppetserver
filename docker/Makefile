NAMESPACE ?= puppet
git_describe = $(shell git describe)
vcs_ref := $(shell git rev-parse HEAD)
build_date := $(shell date -u +%FT%T)
hadolint_available := $(shell hadolint --help > /dev/null 2>&1; echo $$?)
hadolint_command := hadolint
hadolint_container := ghcr.io/hadolint/hadolint:latest

export BUNDLE_PATH = $(PWD)/.bundle/gems
export BUNDLE_BIN = $(PWD)/.bundle/bin
export GEMFILE = $(PWD)/Gemfile
export DOCKER_BUILDKIT ?= 1
PUPPERWARE_ANALYTICS_STREAM ?= dev

ifeq ($(IS_RELEASE),true)
	VERSION ?= $(shell echo $(git_describe) | sed 's/-.*//')
	PRODUCT ?= puppetserver
	# to work around failures that occur between when the repo is tagged and when the package
	# is actually shipped, see if this version exists in dujour
	PUBLISHED_VERSION ?= $(shell curl --silent 'https://updates.puppetlabs.com/?product=$(PRODUCT)&version=$(VERSION)' | jq '."version"' | tr -d '"')
	# For our containers built from packages, we want those to be built once then never changed
	# so check to see if that container already exists on dockerhub
	CONTAINER_EXISTS = $(shell DOCKER_CLI_EXPERIMENTAL=enabled docker manifest inspect $(NAMESPACE)/puppetserver:$(VERSION) > /dev/null 2>&1; echo $$?)
ifeq ($(CONTAINER_EXISTS),0)
	SKIP_BUILD ?= true
else ifneq ($(VERSION),$(PUBLISHED_VERSION))
	SKIP_BUILD ?= true
endif

	BUILD_TYPE ?= release
	LATEST_VERSION ?= latest
else
	BUILD_TYPE ?= edge
	VERSION ?= edge
	IS_LATEST := false
endif

prep:
	@git fetch --unshallow ||:
	@git fetch origin 'refs/tags/*:refs/tags/*'
ifeq ($(SKIP_BUILD),true)
	@echo "SKIP_BUILD is true, exiting with 1"
	@exit 1
endif

lint:
ifeq ($(hadolint_available),0)
	@$(hadolint_command) puppetserver/Dockerfile
else
	@docker pull $(hadolint_container)
	@docker run --rm -v $(PWD)/puppetserver/Dockerfile:/Dockerfile \
		-i $(hadolint_container) $(hadolint_command) Dockerfile
endif

build: prep
	docker buildx build \
		${DOCKER_BUILD_FLAGS} \
		--load \
		--pull \
		--build-arg build_type=$(BUILD_TYPE) \
		--build-arg vcs_ref=$(vcs_ref) \
		--build-arg build_date=$(build_date) \
		--build-arg version=$(VERSION) \
		--build-arg pupperware_analytics_stream=$(PUPPERWARE_ANALYTICS_STREAM) \
		--file puppetserver/Dockerfile \
		--tag $(NAMESPACE)/puppetserver:$(VERSION) $(PWD)/..
ifeq ($(IS_LATEST),true)
	@docker tag $(NAMESPACE)/puppetserver:$(VERSION) \
		$(NAMESPACE)/puppetserver:$(LATEST_VERSION)
endif

test: prep
	@bundle install --path $$BUNDLE_PATH --gemfile $$GEMFILE --with test
	@bundle update
	@PUPPET_TEST_DOCKER_IMAGE=$(NAMESPACE)/puppetserver:$(VERSION) \
		bundle exec --gemfile $$GEMFILE \
		rspec --options puppetserver/.rspec spec

push-image: prep
	@docker push puppet/puppetserver:$(VERSION)
ifeq ($(IS_LATEST),true)
	@docker push puppet/puppetserver:$(LATEST_VERSION)
endif

push-readme:
	@docker pull sheogorath/readme-to-dockerhub
	@docker run --rm \
		-v $(PWD)/puppetserver/README.md:/data/README.md \
		-e DOCKERHUB_USERNAME="$(DOCKERHUB_USERNAME)" \
		-e DOCKERHUB_PASSWORD="$(DOCKERHUB_PASSWORD)" \
		-e DOCKERHUB_REPO_PREFIX=puppet \
		-e DOCKERHUB_REPO_NAME=puppetserver \
		sheogorath/readme-to-dockerhub

publish: push-image push-readme

.PHONY: prep lint build test publish push-image push-readme
