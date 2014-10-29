# SSL Initialization will already have occurred if we are installing in upgrade
# mode.
if not (test_config[:puppetserver_install_mode] == :upgrade)
  step "Validate Sign Cert."
    puppetserver_initialize_ssl
end
