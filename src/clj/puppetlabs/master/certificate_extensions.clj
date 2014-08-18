(ns puppetlabs.master.certificate-extensions)

; See the follwing reference for more info:
; https://docs.puppetlabs.com/puppet/latest/reference/ssl_attributes_extensions.html

(def ppRegCertExt
  "The OID for the extension with shortname 'ppRegCertExt'."
  "1.3.6.1.4.1.34380.1.1")

(def ppPrivCertExt
  "The OID for the extension with shortname 'ppPrivCertExt'."
  "1.3.6.1.4.1.34380.1.2")