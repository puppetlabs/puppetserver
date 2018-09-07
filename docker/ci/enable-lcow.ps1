$Env:GOPATH = 'c:\gopath'
$Env:PATH = "${Env:GOPATH}\bin;c:\go\bin;${Env:GOPATH}\go\bin;${Env:PATH}"

go version
go env

Push-Location $Env:Temp

# upgrade the docker engine with experimental LCOW support
Invoke-WebRequest -OutFile docker-master.zip https://master.dockerproject.com/windows/x86_64/docker.zip
# Appveyor has the engine installed to C:\Program Files\Docker\Docker\Resources\dockerd.exe
Expand-Archive -Path docker-master.zip -DestinationPath . -Force

# AppVeyor image Visual Studio 2017
# $dockerHome = "$Env:ProgramFiles\Docker\Docker\Resources"
# AppVeyor image Visual Studio 2017 Preview
$dockerHome = "$Env:ProgramFiles\Docker\"

Stop-Service docker
Copy-Item .\docker\* $dockerHome
Start-Service docker

docker version

# acquire and build linuxkit from fork
go get -u github.com/Iristyle/linuxkit/src/cmd/linuxkit

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
