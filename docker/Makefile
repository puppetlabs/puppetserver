NAMESPACE ?= puppet
git_describe = $(shell git describe)
vcs_ref := $(shell git rev-parse HEAD)
build_date := $(shell date -u +%FT%T)
hadolint_available := $(shell hadolint --help > /dev/null 2>&1; echo $$?)
hadolint_command := hadolint --ignore DL3008 --ignore DL3018 --ignore DL3028 --ignore DL4000 --ignore DL4001
hadolint_container := hadolint/hadolint:latest
pwd := $(shell pwd)
export BUNDLE_PATH = $(pwd)/.bundle/gems
export BUNDLE_BIN = $(pwd)/.bundle/bin
export GEMFILE = $(pwd)/Gemfile

version = $(shell echo $(git_describe) | sed 's/-.*//')
dockerfile := Dockerfile
PUPPERWARE_ANALYTICS_STREAM ?= dev

prep:
	@git fetch --unshallow ||:
	@git fetch origin 'refs/tags/*:refs/tags/*'

lint:
ifeq ($(hadolint_available),0)
	@$(hadolint_command) puppetserver-standalone/$(dockerfile)
	@$(hadolint_command) puppetserver/$(dockerfile)
else
	@docker pull $(hadolint_container)
	@docker run --rm -v $(PWD)/puppetserver-standalone/$(dockerfile):/Dockerfile \
		-i $(hadolint_container) $(hadolint_command) Dockerfile
	@docker run --rm -v $(PWD)/puppetserver/$(dockerfile):/Dockerfile \
		-i $(hadolint_container) $(hadolint_command) Dockerfile
endif

build: prep
	@docker build \
		--pull \
		--build-arg vcs_ref=$(vcs_ref) \
		--build-arg build_date=$(build_date) \
		--build-arg version=$(version) \
		--build-arg pupperware_analytics_stream=$(PUPPERWARE_ANALYTICS_STREAM) \
		--file puppetserver-standalone/$(dockerfile) \
		--tag $(NAMESPACE)/puppetserver-standalone:$(version) $(pwd)/..
	@docker build \
		--build-arg namespace=$(NAMESPACE) \
		--build-arg vcs_ref=$(vcs_ref) \
		--build-arg build_date=$(build_date) \
		--build-arg version=$(version) \
		--build-arg pupperware_analytics_stream=$(PUPPERWARE_ANALYTICS_STREAM) \
		--file puppetserver/$(dockerfile) \
		--tag $(NAMESPACE)/puppetserver:$(version) \
		puppetserver
ifeq ($(IS_LATEST),true)
	@docker tag $(NAMESPACE)/puppetserver-standalone:$(version) \
		$(NAMESPACE)/puppetserver-standalone:latest
	@docker tag $(NAMESPACE)/puppetserver:$(version) \
		$(NAMESPACE)/puppetserver:latest
endif

test: prep
	@bundle install --path $$BUNDLE_PATH --gemfile $$GEMFILE
	@bundle update
	@PUPPET_TEST_DOCKER_IMAGE=$(NAMESPACE)/puppetserver-standalone:$(version) \
		bundle exec --gemfile $$GEMFILE \
		rspec --options puppetserver-standalone/.rspec spec
	@PUPPET_TEST_DOCKER_IMAGE=$(NAMESPACE)/puppetserver:$(version) \
		bundle exec --gemfile $$GEMFILE \
		rspec --options puppetserver/.rspec spec

push-image: prep
	@docker push puppet/puppetserver-standalone:$(version)
	@docker push puppet/puppetserver:$(version)
ifeq ($(IS_LATEST),true)
	@docker push puppet/puppetserver-standalone:latest
	@docker push puppet/puppetserver:latest
endif

push-readme:
	@docker pull sheogorath/readme-to-dockerhub
	@docker run --rm \
		-v $(PWD)/puppetserver-standalone/README.md:/data/README.md \
		-e DOCKERHUB_USERNAME="$(DISTELLI_DOCKER_USERNAME)" \
		-e DOCKERHUB_PASSWORD="$(DISTELLI_DOCKER_PW)" \
		-e DOCKERHUB_REPO_PREFIX=puppet \
		-e DOCKERHUB_REPO_NAME=puppetserver-standalone \
		sheogorath/readme-to-dockerhub
	@docker run --rm \
		-v $(PWD)/puppetserver/README.md:/data/README.md \
		-e DOCKERHUB_USERNAME="$(DISTELLI_DOCKER_USERNAME)" \
		-e DOCKERHUB_PASSWORD="$(DISTELLI_DOCKER_PW)" \
		-e DOCKERHUB_REPO_PREFIX=puppet \
		-e DOCKERHUB_REPO_NAME=puppetserver \
		sheogorath/readme-to-dockerhub

publish: push-image push-readme

.PHONY: prep lint build test publish push-image push-readme
