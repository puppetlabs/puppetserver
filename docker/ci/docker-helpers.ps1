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
