(ns puppetlabs.services.ca.ca-testutils
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetserver.certificate-authority :as ca]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]))

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
        :hostcrl        (str ssldir "/certs/crl.pem")
        :hostprivkey    (str ssldir "/private_keys/" hostname ".pem")
        :hostpubkey     (str ssldir "/public_keys/" hostname ".pem")
        :localcacert    (str ssldir "/certs/ca.pem")
        :privatekeydir (str ssldir "/private_keys")
        :requestdir     (str ssldir "/certificate_requests")
        :csr-attributes (str confdir "/csr_attributes.yaml")
        :keylength      512})))

(defn ca-settings
  "CA configuration settings with defaults appropriate for testing.
   All file and directory paths will be rooted at the static 'cadir'
   in dev-resources, unless a different `cadir` is provided."
  [cadir]
  {:access-control                   {:certificate-status {:client-whitelist ["localhost"]}}
   :autosign                         true
   :allow-authorization-extensions   false
   :allow-duplicate-certs            false
   :allow-subject-alt-names          false
   :ca-name                          "test ca"
   :ca-ttl                           1
   :cacrl                            (str cadir "/ca_crl.pem")
   :cacert                           (str cadir "/ca_crt.pem")
   :cakey                            (str cadir "/ca_key.pem")
   :capub                            (str cadir "/ca_pub.pem")
   :cert-inventory                   (str cadir "/inventory.txt")
   :csrdir                           (str cadir "/requests")
   :keylength                        512
   :manage-internal-file-permissions true
   :signeddir                        (str cadir "/signed")
   :serial                           (str cadir "/serial")
   :ruby-load-path                   jruby-testutils/ruby-load-path
   :gem-path                         jruby-testutils/gem-path
   :infra-nodes-path                 (str cadir "/ca/infra_inventory.txt")
   :infra-node-serials-path          (str cadir "/infra_serials")
   :infra-crl-path                   (str cadir "/infra_crl.pem")
   :enable-infra-crl                 false})

(defn ca-sandbox!
  "Copy the `cadir` to a temporary directory and return
   the 'ca-settings' map rooted at the temporary directory.
   The directory will be deleted when the JVM exits."
  [cadir]
  (let [tmp-ssldir (ks/temp-dir)]
    (fs/copy-dir cadir tmp-ssldir)
    ;; This is to ensure no warnings are logged during tests
    (ca/set-file-perms (str tmp-ssldir "/ca/ca_key.pem") "rw-r-----")
    (ca-settings (str tmp-ssldir "/ca"))))
