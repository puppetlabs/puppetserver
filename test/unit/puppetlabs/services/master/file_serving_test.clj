(ns puppetlabs.services.master.file-serving-test
  (:require [clojure.test :refer [deftest is testing]]
            [me.raynes.fs :as fs]
            [puppetlabs.services.master.file-serving :refer [get-project-root
                                                             find-project-file
                                                             get-project-modulepath
                                                             read-attributes]])
  (:import java.nio.file.Paths))

(deftest bolt-projects-test
  (let [bolt-builtin-content-dir ["dev-resources/puppetlabs/services/master/master_core_test/builtin_bolt_content"]
        bolt-projects-dir "./dev-resources/puppetlabs/services/master/master_core_test/bolt_projects"]
    (testing "find the root of a bolt project"
      (testing "regular project"
        (is (= (str bolt-projects-dir "/local_23")
               (get-project-root bolt-projects-dir "local_23"))))

      (testing "embedded_e19e09 project (Boltdir)"
        (is (= (str bolt-projects-dir "/embedded_e19e09/Boltdir")
               (get-project-root bolt-projects-dir "embedded_e19e09")))))

    (testing "finding a file in a project"
      (testing "in the modules mount"
        (is (= (fs/file (str bolt-projects-dir "/local_23/modules/helpers/files/marco.sh" ))
               (fs/file (find-project-file bolt-builtin-content-dir bolt-projects-dir "local_23" "modules" "helpers" "marco.sh"))))
        (is (= (fs/file (str bolt-projects-dir "/local_23/.modules/utilities/files/etc/greeting" ))
               (fs/file (find-project-file bolt-builtin-content-dir bolt-projects-dir "local_23" "modules" "utilities" "etc/greeting")))))

    (testing "finding a script in a project"
      (testing "in the scripts mount"
        (is (= (fs/file (str bolt-projects-dir "/local_23/modules/helpers/scripts/yogurt.sh" ))
               (fs/file (find-project-file bolt-builtin-content-dir bolt-projects-dir "local_23" "scripts" "helpers" "yogurt.sh"))))))

      (testing "returns nil when a component is not found"
        (is (= nil
               (find-project-file bolt-builtin-content-dir bolt-projects-dir "fake" "modules" "helpers" "marco.sh")))
        (is (= nil
               (find-project-file bolt-builtin-content-dir bolt-projects-dir "local_23" "fake" "helpers" "marco.sh")))
        (is (= nil
               (find-project-file bolt-builtin-content-dir bolt-projects-dir "local_23" "modules" "fake" "marco.sh")))
        (is (= nil
               (find-project-file bolt-builtin-content-dir bolt-projects-dir "local_23" "modules" "helpers" "fake")))
        (is (= nil
               (find-project-file bolt-builtin-content-dir nil "local_23" "modules" "helpers" "marco.sh")))))))

(deftest get-project-modulepath-test
  (testing "it returns the default modulepath when none is supplied"
    (is (= ["modules" ".modules"]
           (get-project-modulepath {}))))

  (testing "if a modulepath is supplied, then it returns modulepath + '.modules'"
    (let [modulepath ["dir" "otherdir"]]
      (is (= ["dir" "otherdir" ".modules"]
             (get-project-modulepath {:modulepath modulepath}))))))

(deftest metadata
  (let [path (Paths/get
              "./dev-resources/puppetlabs/services/master/master_core_test/bolt_projects/local_23/modules/helpers/files/marco.sh"
              (into-array String []))
        attributes (read-attributes path "md5" true)]
    (testing "just get the metadata"
      (is (= "file" (:type attributes))))))
