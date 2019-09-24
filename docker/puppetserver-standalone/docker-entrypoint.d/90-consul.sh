#!/bin/sh

if [ "$CONSUL_ENABLED" = "true" ]; then
  ipaddress="$(ifconfig eth0 | grep 'inet' | tr -s ' ' | cut -d ' ' -f 3)"

  cat <<SERVICEDEF > /puppet-service.json
{
  "name": "puppet",
  "id": "$HOSTNAME",
  "port": $PUPPET_MASTERPORT,
  "address": "$ipaddress",
  "checks": [
    {
      "http": "https://${HOSTNAME}:${PUPPET_MASTERPORT}/status/v1/simple",
      "tls_skip_verify": true,
      "interval": "1s",
      "deregister_critical_service_after": "5m"
    }
  ]
}
SERVICEDEF

  curl \
    --request PUT \
    --data @puppet-service.json \
    http://$CONSUL_HOSTNAME:$CONSUL_PORT/v1/agent/service/register
fi
