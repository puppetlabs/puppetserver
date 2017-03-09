(ns puppetlabs.services.analytics.analytics-service-test
  (:require
    [clojure.test :refer :all]
    [puppetlabs.dujour.version-check :as version-check]
    [puppetlabs.puppetserver.bootstrap-testutils :as bootstrap-testutils]
    [puppetlabs.trapperkeeper.testutils.logging :as logutils]))

(deftest ^:integration version-check-test
  (testing "master calls into the dujour version check library using the correct values"
    ; This promise will store the parameters passed to the version-check-test-fn, which allows us to keep the
    ; assertions about their values inside the version-check-test and will also ensure failures will appear if
    ; the master stops calling the check-for-updates! function
    (let [version-check-params (promise)
          version-check-test-fn (fn [request-values update-server-url]
                                  (deliver version-check-params
                                           {:request-values request-values
                                            :update-server-url update-server-url}))]
      (with-redefs
       [version-check/check-for-updates! version-check-test-fn]
        (logutils/with-test-logging
         (bootstrap-testutils/with-puppetserver-running-with-mock-jrubies
          "Mocking is safe here because we're not doing anything with JRubies, just making sure
          the service starts and makes the right dujour calls"
          app
          {:jruby-puppet {:max-active-instances 1}
           :webserver {:port 8081}
           :product {:update-server-url "http://notarealurl/"
                     :name {:group-id "puppets"
                            :artifact-id "yoda"}}}
          (let [params-received (deref version-check-params 30000
                                       {:request-values
                                        {:product-name
                                         :no-product-name-received-before-time-out-reached}
                                        :update-server-url
                                        :no-update-server-url-received-before-time-out-reached})]
            (is (= {:group-id "puppets" :artifact-id "yoda"}
                   (get-in params-received [:request-values :product-name])))
            (is (= "http://notarealurl/"
                   (:update-server-url params-received)))))))))

  (testing "master does not make an analytics call to dujour if opt-out exists"
    ; This promise will store the parameters passed to the version-check-test-fn, which allows us to keep the
    ; assertions about their values inside the version-check-test and will also ensure failures will appear if
    ; the master stops calling the check-for-updates! function
    (let [version-check-params  (promise)
          version-check-test-fn (fn [request-values update-server-url]
                                  (deliver version-check-params
                                           {:request-values request-values
                                            :update-server-url update-server-url}))]
      (with-redefs
       [version-check/check-for-updates! version-check-test-fn]
        (logutils/with-test-logging
         (bootstrap-testutils/with-puppetserver-running-with-mock-jrubies
          "Mocking is safe here because we're not doing anything with JRubies, just making sure
          the service starts and makes the right dujour calls"
          app
          {:jruby-puppet {:max-active-instances 1}
           :webserver {:port 8081}
           :product {:update-server-url "http://notarealurl/"
                     :name {:group-id "puppets"
                            :artifact-id "yoda"}
                     :check-for-updates false}}
          (is (logutils/logged?
               #"Not checking for updates - opt-out setting exists" :info))
          (let [params-received (deref version-check-params 100
                                       :no-params-received-before-time-out-reached)]
            (is (= :no-params-received-before-time-out-reached params-received)))))))))
