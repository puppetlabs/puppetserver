# runs a registry on
function Start-DockerRegistry
{
  docker pull registry:2
  docker run -d -p 5000:5000 --restart=always --name registry registry:2
}

# runs a registry on top of Windows Nano Server
# requires LCOW on to test puppetserver and run this Windows container
function Start-DockerRegistryWindows
{
  # Run the registry container
  # https://hub.docker.com/r/stefanscherer/registry-windows/
  New-Item -Type Directory c:\registry
  docker pull stefanscherer/registry-windows:2.6.2
  docker run -d -p 5000:5000 --restart=always --name registry -v C:\registry:C:\registry stefanscherer/registry-windows:2.6.2
}

function Get-DockerConfigFilePath
{
  $path = "${ENV:ALLUSERSPROFILE}\docker\config\daemon.json"

  # retrieve the path to daemon.json from docker service config, if present
  sc.exe qc docker |
    ? { $_ -match 'BINARY_PATH_NAME\s*:\s*(.*)' } |
    ? { $_ -match '--config-file\s*(.*daemon\.json)' }

  # found BINARY_PATH_NAME which contains --config-file *AND* file exists
  if (($Matches.Count -gt 0) -and ((Test-Path -Path $Matches[1]) -eq $True))
  {
    $path = $Matches[1]
  }

  Write-Host "Config file path: $path"
  return $path
}

function Get-DockerConfig
{
  $json = '{}'
  $configFilePath = Get-DockerConfigFilePath

  if (Test-Path $configFilePath)
  {
    $json = Get-Content $configFilePath
    Write-Host "Existing config:`n`n$json`n`n"
  }
  return ($json | ConvertFrom-Json)
}

function Set-DockerConfig($hash)
{
  $configFilePath = Get-DockerConfigFilePath
  $utf8NoBom = New-Object System.Text.UTF8Encoding $False
  # making sure to write a UTF-8 file with NO BOM
  [System.IO.File]::WriteAllLines($configFilePath, ($hash | ConvertTo-Json), $utf8NoBom)
  Write-Host "Updated config file:`n`n$(Get-Content $configFilePath)`n`n"
}

function Add-UntrustedDockerRegistry($Address)
{
  $config = Get-DockerConfig
  # Reconfigure the docker daemon to use the registry untrusted
  # add the tcp listener on loopback for docker-api gem
  # $config.hosts += 'tcp://127.0.0.1:2375'
  if (-not $config.ContainsKey('insecure-registries'))
  {
    $config.'insecure-registries' = @()
  }
  $config.'insecure-registries' += @("127.0.0.1:5000")

  Set-DockerConfig $config
}


Start-DockerRegistry
Add-UntrustedDockerRegistry -Address '127.0.0.1:5000'

# Restart service for configuration to take effect
Restart-Service docker
