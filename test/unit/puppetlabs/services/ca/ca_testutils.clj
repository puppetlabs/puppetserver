(ns puppetlabs.services.ca.ca-testutils
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.kitchensink.core :as ks]))

(defn assert-subject [o subject]
  (is (= subject (-> o .getSubjectX500Principal .getName))))

(defn assert-issuer [o issuer]
  (is (= issuer (-> o .getIssuerX500Principal .getName))))

(defn master-settings
  "Master configuration settings with defaults appropriate for testing.
   All file and directory paths will be rooted at the provided `confdir`."
  ([confdir] (master-settings confdir "localhost"))
  ([confdir hostname]
     (let [ssldir (str confdir "/ssl")]
       {:certdir        (str ssldir "/certs")
        :dns-alt-names  ""
        :hostcert       (str ssldir "/certs/" hostname ".pem")
        :hostprivkey    (str ssldir "/private_keys/" hostname ".pem")
        :hostpubkey     (str ssldir "/public_keys/" hostname ".pem")
        :localcacert    (str ssldir "/certs/ca.pem")
        :requestdir     (str ssldir "/certificate_requests")
        :csr-attributes (str confdir "/csr_attributes.yaml")})))

(defn ca-settings
  "CA configuration settings with defaults appropriate for testing.
   All file and directory paths will be rooted at the static 'cadir'
   in dev-resources, unless a different `cadir` is provided."
  [cadir]
  {:access-control        {:certificate-status {:client-whitelist ["localhost"]}}
   :autosign              true
   :allow-duplicate-certs false
   :ca-name               "test ca"
   :ca-ttl                1
   :cacrl                 (str cadir "/ca_crl.pem")
   :cacert                (str cadir "/ca_crt.pem")
   :cakey                 (str cadir "/ca_key.pem")
   :capub                 (str cadir "/ca_pub.pem")
   :cert-inventory        (str cadir "/inventory.txt")
   :csrdir                (str cadir "/requests")
   :signeddir             (str cadir "/signed")
   :serial                (str cadir "/serial")
   :ruby-load-path        ["ruby/puppet/lib" "ruby/facter/lib"]})

(defn ca-sandbox!
  "Copy the `cadir` to a temporary directory and return
   the 'ca-settings' map rooted at the temporary directory.
   The directory will be deleted when the JVM exits."
  [cadir]
  (let [tmp-ssldir (ks/temp-dir)]
    (fs/copy-dir cadir tmp-ssldir)
    (ca-settings (str tmp-ssldir "/ca"))))
