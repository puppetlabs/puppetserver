NAMESPACE ?= puppet
git_describe = $(shell git describe)
vcs_ref := $(shell git rev-parse HEAD)
build_date := $(shell date -u +%FT%T)
hadolint_available := $(shell hadolint --help > /dev/null 2>&1; echo $$?)
hadolint_command := hadolint --ignore DL3008 --ignore DL3018 --ignore DL3028 --ignore DL4000 --ignore DL4001
hadolint_container := hadolint/hadolint:latest
export BUNDLE_PATH = $(PWD)/.bundle/gems
export BUNDLE_BIN = $(PWD)/.bundle/bin
export GEMFILE = $(PWD)/Gemfile
PUPPERWARE_ANALYTICS_STREAM ?= dev

ifeq ($(IS_RELEASE),true)
	VERSION ?= $(shell echo $(git_describe) | sed 's/-.*//')
	LATEST_VERSION ?= latest
	dockerfile := Dockerfile-release
	dockerfile_context := puppetserver
else
	VERSION ?= edge
	IS_LATEST := false
	dockerfile := Dockerfile
	dockerfile_context := $(PWD)/..
endif

prep:
	@git fetch --unshallow ||:
	@git fetch origin 'refs/tags/*:refs/tags/*'

lint:
ifeq ($(hadolint_available),0)
	@$(hadolint_command) puppetserver-base/Dockerfile
	@$(hadolint_command) puppetserver/$(dockerfile)
else
	@docker pull $(hadolint_container)
	@docker run --rm -v $(PWD)/puppetserver/$(dockerfile):/Dockerfile \
		-i $(hadolint_container) $(hadolint_command) Dockerfile
	@docker run --rm -v $(PWD)/puppetserver-base/Dockerfile:/Dockerfile \
		-i $(hadolint_container) $(hadolint_command) Dockerfile
endif

build: prep
	docker build \
		--pull \
		--build-arg vcs_ref=$(vcs_ref) \
		--build-arg build_date=$(build_date) \
		--build-arg version=$(VERSION) \
		--build-arg pupperware_analytics_stream=$(PUPPERWARE_ANALYTICS_STREAM) \
		--file puppetserver-base/Dockerfile \
		--tag $(NAMESPACE)/puppetserver-base:$(VERSION) puppetserver-base
	docker build \
		--build-arg namespace=$(NAMESPACE) \
		--build-arg vcs_ref=$(vcs_ref) \
		--build-arg build_date=$(build_date) \
		--build-arg version=$(VERSION) \
		--file puppetserver/$(dockerfile) \
		--tag $(NAMESPACE)/puppetserver:$(VERSION) $(dockerfile_context)
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
