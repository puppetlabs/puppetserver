function Enable-DockerExperimental
{
  $config = Get-DockerConfig
  if (-not $config.PSObject.Properties.Match('experimental'))
  {
    $config.experimental = $True
  }
  else
  {
    $config | Add-Member -MemberType NoteProperty -Name 'experimental' -Value $True
  }

  Set-DockerConfig $config
}

Push-Location $Env:Temp

# upgrade the docker engine with experimental LCOW support
Invoke-WebRequest -OutFile docker-master.zip https://master.dockerproject.com/windows/x86_64/docker.zip
# Appveyor has the engine installed to C:\Program Files\Docker\Docker\Resources\dockerd.exe
Expand-Archive -Path docker-master.zip -DestinationPath . -Force

$dockerHome = "$Env:ProgramFiles\Docker\"
Stop-Service docker
Copy-Item .\docker\* $dockerHome

Enable-DockerExperimental

Start-Service docker

docker version
docker info

# run alpine to make sure Linux containers are working
docker run alpine

# acquire and build linuxkit from fork
go get -v -u github.com/Iristyle/linuxkit/src/cmd/linuxkit

# validate it was built and in path
& linuxkit.exe version

# clone the LCOW source and build the image with linuxkit
git clone https://github.com/linuxkit/lcow
Push-Location lcow
& linuxkit.exe build lcow.yml

# move the LCOW VM into the expected location
$containerHome = "$Env:ProgramFiles\Linux Containers"
New-Item $containerHome -Type Directory -Force
Copy-Item .\lcow-initrd.img "$containerHome\initrd.img"
Copy-Item .\lcow-kernel "$containerHome\kernel"
Pop-Location

# server doesn't require registration as experimental as its already setup that way
# re-register the service
# & $dockerHome\dockerd.exe --register-service --experimental

Pop-Location
Start-Service docker
