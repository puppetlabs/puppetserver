#!/bin/sh

if [ "$CONSUL_ENABLED" = "true" ]; then
  ipaddress="$(ifconfig eth0 | grep 'inet addr' | cut -d ':' -f 2 | cut -d ' ' -f 1)"
  consul_hostname="${CONSUL_HOSTNAME:-consul}"
  consul_port="${CONSUL_PORT:-8500}"

  cat <<SERVICEDEF > /puppet-service.json
{
  "name": "puppet",
  "id": "$HOSTNAME",
  "port": 8140,
  "address": "$ipaddress",
  "checks": [
    {
      "http": "https://${HOSTNAME}:8140/${PUPPET_HEALTHCHECK_ENVIRONMENT}/status/test",
      "tls_skip_verify": true,
      "interval": "30s",
      "deregister_critical_service_after": "5m"
    }
  ]
}
SERVICEDEF

  curl \
    --request PUT \
    --data @puppet-service.json \
    http://$consul_hostname:$consul_port/v1/agent/service/register
fi
