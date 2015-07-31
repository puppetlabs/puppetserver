(ns puppetlabs.puppetserver.environment-cache-test
  (:require [puppetlabs.puppetserver.environment-cache :refer :all]
            [clojure.test :refer :all]))

(defn sample-data
  ([] (sample-data "production"))
  ([environment]
    {:pre [(string? environment)]}
    {environment
       {"/Users/batmanandrobin/.puppet-server/2.x/code/environments/production/manifests/init.pp" []
        "/Users/batmanandrobin/.puppet-server/2.x/code/environments/production/modules/simmons/manifests/binary_file.pp"
          [{"name" "simmons::binary_file",
            "params" [{"name" "studio"}]}]
        "/Users/batmanandrobin/.puppet-server/2.x/code/environments/production/modules/simmons/manifests/content_file.pp"
          [{"name" "simmons::content_file"
            "params" [{"name" "studio"}]}]
        "/Users/batmanandrobin/.puppet-server/2.x/code/environments/production/modules/simmons/manifests/custom_fact_output.pp"
          [{"name" "simmons::custom_fact_output"
            "params" [{"name" "studio"}]}]
        "/Users/batmanandrobin/.puppet-server/2.x/code/environments/production/modules/simmons/manifests/external_fact_output.pp"
          [{"name" "simmons::external_fact_output"
            "params" [{"name" "studio"}]}]
        "/Users/batmanandrobin/.puppet-server/2.x/code/environments/production/modules/simmons/manifests/init.pp"
          [{"name" "simmons"
            "params" [{"name" "studio"
                       "default_source" "undef"}
                      {"name" "exercises"
                       "default_literal" ["simmons::content_file"
                                          "simmons::source_file"
                                          "simmons::binary_file"
                                          "simmons::recursive_directory"
                                          "simmons::mount_point_source_file"
                                          "simmons::mount_point_binary_file"
                                          "simmons::custom_fact_output"
                                          "simmons::external_fact_output"]}]}]
        "/Users/batmanandrobin/.puppet-server/2.x/code/environments/production/modules/simmons/manifests/mount_point_binary_file.pp"
          [{"name" "simmons::mount_point_binary_file"
            "params" [{"name" "studio"}]}]
        "/Users/batmanandrobin/.puppet-server/2.x/code/environments/production/modules/simmons/manifests/mount_point_source_file.pp"
          [{"name" "simmons::mount_point_source_file"
            "params" [{"name" "studio"}]}]
        "/Users/batmanandrobin/.puppet-server/2.x/code/environments/production/modules/simmons/manifests/recursive_directory.pp"
          [{"name" "simmons::recursive_directory"
            "params" [{"name" "studio"}]}]
        "/Users/batmanandrobin/.puppet-server/2.x/code/environments/production/modules/simmons/manifests/source_file.pp"
          [{"name" "simmons::source_file"
            "params" [{"name" "studio"}]}]}}))

(deftest environment-cache
  (testing "Create a new empty cache"
    (is (= {} (create-cache))))

  (testing "Initializing and clearing a cache"
    (let [cache (atom (create-cache))
          env "production"
          env-data (sample-data env)]
      (swap! cache reset-cache env-data)
      (is (= env-data (get-all-environments-info @cache)))

      (swap! cache reset-cache)
      (is (= {} (get-all-environments-info @cache)))))

  (testing "Adding environments by name and retrieveing them "
    (let [cache (atom (create-cache))
          prod-data (sample-data "production")
          test-data (sample-data "test")]
      (swap! cache store-environment-info prod-data)
      (is (= (get prod-data "production")
             (get-environment-info @cache "production")))

      (swap! cache store-environment-info test-data)
      (is (= (get test-data "test")
             (get-environment-info @cache "test")))
      (is (= (get prod-data "production")
             (get-environment-info @cache "production")))
      (is (= (merge test-data prod-data)
             (get-all-environments-info @cache)))

      (swap! cache remove-environment-info "test")
      (is (nil? (get-environment-info @cache "test")))
      (is (= (get prod-data "production")
             (get-environment-info @cache "production")))

      (swap! cache reset-cache)
      (is (nil? (get-environment-info @cache "production")))
      (is (= {} (get-all-environments-info @cache))))))
