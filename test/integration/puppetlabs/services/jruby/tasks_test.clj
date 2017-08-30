(ns puppetlabs.services.jruby.tasks-test
  (:require [clojure.test :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [puppetlabs.services.master.master-core :as mc]
            [me.raynes.fs :as fs]
            [cheshire.core :as cheshire]
            [schema.core :as schema]
            [schema.test :as schema-test]
            [puppetlabs.puppetserver.testutils :as testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap])
  (:import [java.io File]
           [org.jruby.exceptions RaiseException]))

(use-fixtures :once schema-test/validate-schemas)

(def TaskOptions
  {:name schema/Str
   :module-name schema/Str
   :metadata? schema/Bool
   :number-of-files (schema/pred #(<= % 5))})

(schema/defn gen-empty-task
  "Assumes tasks dir already exists -- generates a set of task files for
  a single task. All the generated payload files are empty, and all generated
  metadata files contain {\"meta\": \"data\"}

  This function exists in addition to the tasks-generating utilities in
  puppetlabs.puppetserver.testutils primarily because those other utilites only
  generate things inside a hardcoded conf-dir at 'target/master-conf'."
  [env-dir :- (schema/cond-pre File schema/Str)
   task :- TaskOptions]
  (let [task-name (:name task)
        extensions '(".rb" "" ".sh" ".exe" ".py")
        task-dir (fs/file env-dir "modules" (:module-name task) "tasks")]
    (fs/mkdirs task-dir)
    (when (:metadata? task)
      (spit (fs/file task-dir (str task-name ".json")) "{\"meta\": \"data\"}"))
    (dotimes [n (:number-of-files task)]
      (fs/create (fs/file task-dir (str task-name (nth extensions n ".rb")))))))

(defn gen-empty-tasks
  "Tasks is a list of task maps, with keys:
  :name String, file name of task
  :module-name String, name of module task is in
  :metadata? Boolean, whether to generate a metadata file
  :number-of-files Number, how many executable files to generate for the task (0 or more)

  All generated files are empty, except metadata files, which contain the empty JSON object.

  This function exists in addition to the tasks-generating utilities in
  puppetlabs.puppetserver.testutils primarily because those other utilites only
  generate things inside a hardcoded conf-dir at 'target/master-conf'."
  [env-dir tasks]
  (dorun (map (partial gen-empty-task env-dir) tasks)))

(defn create-env
  [env-dir tasks]
  (testutils/create-env-conf env-dir "")
  (gen-empty-tasks env-dir tasks))

(defn env-dir
  [code-dir env-name]
  (fs/file code-dir "environments" env-name))

(defn expected-tasks-info
  [tasks]
  (map (fn [{:keys [name module-name]}]
         {:module {:name module-name}
          :name (if (= "init" name)
                  module-name
                  (str module-name "::" name))})
       tasks))

(schema/defn puppet-tk-config
  [code-dir :- File, conf-dir :- File]
  (jruby-testutils/jruby-puppet-tk-config
   (jruby-testutils/jruby-puppet-config
    {:master-code-dir (.getAbsolutePath code-dir)
     :master-conf-dir (.getAbsolutePath conf-dir)})))

(def puppet-conf-file-contents
  "[main]\nenvironment_timeout=unlimited\nbasemodulepath=$codedir/modules\n")

(deftest ^:integration task-data-test
  (testing "requesting data about a specific task"
    (let [code-dir (ks/temp-dir)
          conf-dir (ks/temp-dir)]

      (testutils/create-file (fs/file conf-dir "puppet.conf") puppet-conf-file-contents)

      (tk-bootstrap/with-app-with-config
        app
        jruby-testutils/jruby-service-and-dependencies
        (puppet-tk-config code-dir conf-dir)
        (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
              instance (jruby-testutils/borrow-instance jruby-service :test)
              jruby-puppet (:jruby-puppet instance)
              env-1-dir (env-dir code-dir "env1")
              env-1-tasks [{:name "install"
                            :module-name "apache"
                            :metadata? true
                            :number-of-files 2}
                           {:name "init"
                            :module-name "apache"
                            :metadata? false
                            :number-of-files 1}]
              get-task-data (fn [env module task]
                              (-> (.getTaskData jruby-puppet env module task)
                                  mc/sort-nested-info-maps))]

          (try (create-env env-1-dir env-1-tasks)
            (testing "when the environment, module, and task do exist"
              (testing "with the init task"
                (let [res (get-task-data "env1" "apache" "init")]
                  (is (nil? (schema/check mc/TaskData res)))
                  (is (some (fn [file-path] (.contains file-path "init"))
                            (:files res)))))

              (testing "with another named task"
                (let [res (get-task-data "env1" "apache" "install")]
                  (is (nil? (schema/check mc/TaskData res)))
                  (is (some (fn [file-path] (.contains file-path "install"))
                            (:files res))))))

            (testing "when the environment does not exist"
              (is (thrown-with-msg? RaiseException
                                    #"(EnvironmentNotFound)"
                                    (get-task-data "env2" "doesnotmatter" "whatever"))))

            (testing "when the module does not exist"
              (is (thrown-with-msg? RaiseException
                                    #"(MissingModule)"
                                    (get-task-data "env1" "notamodule" "install"))))

            (testing "when the module name is invalid"
              (is (thrown-with-msg? RaiseException
                                    #"(MissingModule)"
                                    (get-task-data "env1" "7!" "install"))))

            (testing "when the task does not exist"
              (is (thrown-with-msg? RaiseException
                                    #"(TaskNotFound)"
                                    (get-task-data "env1" "apache" "recombobulate"))))

            (testing "when the task does not exist"
              (is (thrown-with-msg? RaiseException
                                    #"(TaskNotFound)"
                                    (get-task-data "env1" "apache" "recombobulate"))))

            (testing "when the task name is invalid"
              (is (thrown-with-msg? RaiseException
                                    #"(TaskNotFound)"
                                    (get-task-data "env1" "apache" "&&&"))))

            (finally
              (jruby-testutils/return-instance jruby-service instance :test))))))))

(deftest ^:integration all-tasks-test
  (testing "requesting all tasks"
    (let [code-dir (ks/temp-dir)
          conf-dir (ks/temp-dir)]
      (testutils/create-file (fs/file conf-dir "puppet.conf") puppet-conf-file-contents)

      (tk-bootstrap/with-app-with-config
        app
        jruby-testutils/jruby-service-and-dependencies
        (puppet-tk-config code-dir conf-dir)
        (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
              instance (jruby-testutils/borrow-instance jruby-service :test)
              jruby-puppet (:jruby-puppet instance)
              env-1-dir (env-dir code-dir "env1")
              env-1-tasks [{:name "install"
                            :module-name "apache"
                            :metadata? true
                            :number-of-files 2}
                           {:name "init"
                            :module-name "apache"
                            :metadata? false
                            :number-of-files 1}
                           {:name "configure"
                            :module-name "django"
                            :metadata? true
                            :number-of-files 0}]

              get-tasks (fn [env]
                          (.getTasks jruby-puppet env))]

          (try (create-env env-1-dir env-1-tasks)
               (testing "for environment that does exist"
                 (is (= (->> env-1-tasks
                             expected-tasks-info
                             (sort-by :name))
                        (->> (get-tasks "env1")
                             mc/sort-nested-info-maps
                             (sort-by :name)))
                     "Unexpected info retrieved for 'env1'"))

               (testing "for environment that does not exist"
                 (is (nil? (get-tasks "env2"))))

               (finally
                 (jruby-testutils/return-instance jruby-service instance :test))))))))
