$ErrorActionPreference = 'Stop'

function Get-CurrentDirectory
{
  $thisName = $MyInvocation.MyCommand.Name
  [IO.Path]::GetDirectoryName((Get-Content function:$thisName).File)
}

function Get-ContainerVersion
{
  # shallow repositories need to pull remaining code to `git describe` correctly
  if (Test-Path "$(git rev-parse --git-dir)/shallow")
  {
    git pull --unshallow
  }

  # tags required for versioning
  git fetch origin 'refs/tags/*:refs/tags/*'
  (git describe) -replace '-.*', ''
}

function Lint-Dockerfile($Path)
{
  hadolint --ignore DL3008 --ignore DL3018 --ignore DL4000 --ignore DL4001 $Path
}

function Build-Container(
  $Name,
  $Namespace = 'puppet',
  $Version = (Get-ContainerVersion),
  $Vcs_ref = $(git rev-parse HEAD),
  $Pull = $true)
{
  Push-Location (Join-Path (Get-CurrentDirectory) '..')

  $build_date = (Get-Date).ToUniversalTime().ToString('o')
  $docker_args = @(
    '--build-arg', "vcs_ref=$Vcs_ref",
    '--build-arg', "build_date=$build_date",
    '--build-arg', "version=$Version",
    '--build-arg', "namespace=$Namespace",
    '--file', "$Name/Dockerfile",
    '--tag', "$Namespace/${Name}:$Version"
  )

  if ($Pull) {
    $docker_args += '--pull'
  }

  docker build $docker_args $Name

  Pop-Location
}

function Invoke-ContainerTest(
  $Name,
  $Namespace = 'puppet',
  $Version = (Get-ContainerVersion))
{
  Push-Location (Join-Path (Get-CurrentDirectory) '..')

  bundle install --path .bundle/gems
  $ENV:PUPPET_TEST_DOCKER_IMAGE = "$Namespace/${Name}:$Version"
  bundle exec rspec --options $Name/.rspec $Name/spec

  Pop-Location
}

# removes any temporary containers / images used during builds
function Clear-ContainerBuilds
{
  docker container prune --force
  docker image prune --filter "dangling=true" --force
}
