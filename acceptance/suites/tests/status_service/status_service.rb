require 'json'

test_name 'Validate status service is functional'

with_puppet_running_on(master, {}) do
  step 'Make a status service request'
  url = "https://#{master}:8140/status/v1/services?level=debug"
  response = https_request(url, 'GET')

  step 'Validate status response'
  assert_equal("200", response.code,
               "Unexpected response status code for request, " +
                   "body: #{response.body}")
  body = JSON.parse(response.body)
  refute_nil(body, 'Response body unexpectedly nil')
  refute_nil(body["status-service"],
             'Response body unexpectedly did not contain status service: ' +
                 response.body)
end
