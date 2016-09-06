test_name 'Server can mix stops, starts, restarts, and reloads'

puppetservice=options['puppetservice']

def get_process_start_time
  url = "https://#{master}:8140/status/v1/services/status-service?level=debug"
  response = https_request(url, 'GET')
  assert_equal("200", response.code,
               "Unexpected response status code for status request, " +
                   "body: #{response.body}")

  body = JSON.parse(response.body)
  refute_nil(body["status"],
             "No status key in status response, body: #{body}")
  refute_nil(body["status"]["experimental"],
             "No experimental sub key in status response, body: #{body}")
  refute_nil(body["status"]["experimental"]["jvm-metrics"],
             "No jvm-metrics sub key in status response, body: #{body}")
  refute_nil(body["status"]["experimental"]["jvm-metrics"]["start-time-ms"],
             "No start-time-ms sub key in status response, body: #{body}")

  return body["status"]["experimental"]["jvm-metrics"]["start-time-ms"]
end

with_puppet_running_on(master, {}) do
  step 'Get start time for initial service startup'
  initial_start_time=get_process_start_time

  step 'Reload Puppet Server after initial service startup' do
    reload_server
  end

  step 'Confirm start time unchanged after first reload' do
    start_time_after_first_reload=get_process_start_time
    assert_equal(initial_start_time, start_time_after_first_reload,
                 'Start time changed after first reload')
  end

  step 'Stop Puppet Server via service command' do
    on(master, "service #{puppetservice} stop")
    on(master, 'pgrep -f puppet-server-release.jar',
       {:acceptable_exit_codes => [1]})
  end

  step 'Confirm service reload fails when service stopped' do
    reload_server(master, {:acceptable_exit_codes => [1]})
    on(master, 'pgrep -f puppet-server-release.jar',
       {:acceptable_exit_codes => [1]})
  end

  step 'Start Puppet Server via service command' do
    on(master, "service #{puppetservice} start")
  end

  step 'Confirm start time changed after stop and start'
  start_time_after_stop_and_start=get_process_start_time
  assert(start_time_after_stop_and_start > initial_start_time,
         'Start time not later after service stop and start: ' +
             "initial_start_time: #{initial_start_time}, " +
             "new_start_time: #{start_time_after_stop_and_start}")

  step 'Reload Puppet Server after stop and start' do
    reload_server
  end

  step 'Confirm start time unchanged after second reload' do
    start_time_after_second_reload=get_process_start_time
    assert_equal(start_time_after_stop_and_start, start_time_after_second_reload,
                 'Start time changed after second reload')
  end

  step 'Restart Puppet Server via service command' do
    on(master, "service #{puppetservice} restart")
  end

  step 'Confirm start time changed after restart'
  start_time_after_restart=get_process_start_time
  assert(start_time_after_restart > start_time_after_stop_and_start,
         'Start time not later after service restart, ' +
             "previous_start_time: #{start_time_after_stop_and_start}, " +
             "new_start_time: #{start_time_after_restart}")

  step 'Reload Puppet Server after restart' do
    reload_server
  end

  step 'Confirm start time unchanged after third reload' do
    start_time_after_third_reload=get_process_start_time
    assert_equal(start_time_after_restart, start_time_after_third_reload,
                 'Start time changed after third reload')
  end
end
