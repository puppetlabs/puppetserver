test_name "Testing Master/Agent connection"

with_puppet_running_on( master, {} ) do
  on agents, puppet("agent --test --server #{master}")
end
