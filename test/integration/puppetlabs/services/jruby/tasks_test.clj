(ns puppetlabs.services.jruby.tasks-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.pprint :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [puppetlabs.services.master.master-core :as mc]
            [me.raynes.fs :as fs]
            [cheshire.core :as cheshire]
            [schema.core :as schema]
            [schema.test :as schema-test]
            [slingshot.test :refer :all]
            [puppetlabs.puppetserver.testutils :as testutils]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-bootstrap])
  (:import [java.io File ByteArrayInputStream]
           [org.jruby.exceptions RaiseException]))

(schema/defn puppet-tk-config
  [code-dir :- File, conf-dir :- File]
  (jruby-testutils/jruby-puppet-tk-config
   (jruby-testutils/jruby-puppet-config
    {:master-code-dir (.getAbsolutePath code-dir)
     :master-conf-dir (.getAbsolutePath conf-dir)})))

(def puppet-conf-file-contents
  "[main]\nenvironment_timeout=0\nbasemodulepath=$codedir/modules\n")

(def ^:dynamic *code-dir* nil)
(def ^:dynamic *jruby-service* nil)
(def ^:dynamic *jruby-puppet* nil)

(defn with-running-server
  [f]
  (let [code-dir (ks/temp-dir)
        conf-dir (ks/temp-dir)]
    (testutils/create-file (fs/file conf-dir "puppet.conf") puppet-conf-file-contents)
    (tk-bootstrap/with-app-with-config
      app
      jruby-testutils/jruby-service-and-dependencies
      (puppet-tk-config code-dir conf-dir)
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            instance (jruby-testutils/borrow-instance jruby-service :test)
            jruby-puppet (:jruby-puppet instance)]
        (binding [*code-dir* code-dir
                  *jruby-service* jruby-service
                  *jruby-puppet* jruby-puppet]
          (try
            (f)
            (finally
              (jruby-testutils/return-instance jruby-service instance :test))))))))

(use-fixtures :once schema-test/validate-schemas)
(use-fixtures :each with-running-server)

(def TaskOptions
  {:name schema/Str
   :module-name schema/Str
   (schema/optional-key :metadata) schema/Any
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
        task-dir (fs/file env-dir "modules" (:module-name task) "tasks")
        files (concat (get-in task [:metadata :files]) (mapcat :files (get-in task [:metadata :implementations])))]
    (fs/mkdirs task-dir)
    (doseq [file files]
           (cond (str/ends-with? file "/")
                 (do (fs/mkdirs (fs/file env-dir "modules" file))
                   (fs/create (fs/file env-dir "modules" file "temp.rb")))
                 :else (do (fs/mkdirs (fs/parent (fs/file env-dir "modules" file)))
                         (fs/create (fs/file env-dir "modules" file)))))
    (when-let [metadata (:metadata task)]
      (spit (fs/file task-dir (str task-name ".json")) (cheshire/generate-string metadata)))
    (dotimes [n (:number-of-files task)]
      (fs/create (fs/file task-dir (str task-name (nth extensions n ".rb")))))))

(defn gen-empty-tasks
  "Tasks is a list of task maps, with keys:
  :name String, file name of task
  :module-name String, name of module task is in
  :metadata Map of metadata to write
  :number-of-files Number, how many executable files to generate for the task (0 or more)

  All generated files are empty, except metadata files, which contain the empty JSON object.

  This function exists in addition to the tasks-generating utilities in
  puppetlabs.puppetserver.testutils primarily because those other utilites only
  generate things inside a hardcoded conf-dir at 'target/master-conf'."
  [env-dir tasks]
  (dorun (map (partial gen-empty-task env-dir) tasks)))

(defn create-env
  [env-dir tasks]
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

(defn simple-impl-metadata
  [task]
  {"implemenations" [{"name" (str task ".rb")}]})

(deftest ^:integration task-data-test
  (testing "requesting data about a specific task"
    (let [tasks [{:name "install"
                  :module-name "apache"
                  :metadata (simple-impl-metadata "install")
                  :number-of-files 2}
                 {:name "init"
                  :module-name "apache"
                  :number-of-files 1}]
          get-task-data (fn [env module task]
                          (-> (.getTaskData *jruby-puppet* env module task)
                              mc/sort-nested-info-maps))]

      (create-env (env-dir *code-dir* "env1") tasks)
      (testing "when the environment, module, and task do exist"
        (testing "with the init task"
          (let [res (get-task-data "env1" "apache" "init")]
            (is (nil? (schema/check mc/TaskData res)))
            (is (= "init.rb" (-> res :files first :name))
            (is (re-matches #".*init\.rb" (-> res :files first :path))))))

        (testing "with another named task"
          (let [res (get-task-data "env1" "apache" "install")]
            (is (nil? (schema/check mc/TaskData res)))
            (is (= [] (:files res))))))

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
                              (get-task-data "env1" "apache" "&&&")))))))

(deftest ^:integration all-tasks-test
  (testing "requesting all tasks"
    (let [tasks [{:name "install"
                  :module-name "apache"
                  :metadata (simple-impl-metadata "apache")
                  :number-of-files 2}
                 {:name "init"
                  :module-name "apache"
                  :number-of-files 1}
                 {:name "configure"
                  :module-name "django"
                  :metadata { }
                  :number-of-files 0}]
          get-tasks (fn [env]
                      (.getTasks *jruby-puppet* env))]

      (create-env (env-dir *code-dir* "env1") tasks)
      (testing "for environment that does exist"
        (is (= (->> tasks
                    expected-tasks-info
                    (sort-by :name))
               (->> (get-tasks "env1")
                    mc/sort-nested-info-maps
                    (sort-by :name)))
            "Unexpected info retrieved for 'env1'"))

      (testing "for environment that does not exist"
        (is (nil? (get-tasks "env2")))))))

(deftest ^:integration task-details-test
  (testing "getting details for a specific task"
    (let [tasks [{:name "install_mods"
                  :module-name "apache"
                  :metadata {"meta" "data"}
                  :number-of-files 1}
                 {:name "init"
                  :module-name "apache"
                  :number-of-files 1}
                 {:name "init"
                  :module-name "othermod"
                  :metadata {}
                  :number-of-files 1}
                 {:name "impl"
                  :module-name "othermod"
                  :metadata {}
                  :number-of-files 1}
                 {:name "files"
                  :module-name "apache"
                  :metadata {:files ["othermod/files/helper.rb",
                                     "othermod/files/morefiles/"
                                     "apache/lib/puppet/ruby_file.rb"]
                             :implementations [{:name "files.rb"
                                                :files ["othermod/files/impl_helper.rb"]}]}
                  :number-of-files 1}
                 {:name "about"
                  :metadata {}
                  :module-name "apache"
                  :number-of-files 0}]]
      (create-env (env-dir *code-dir* "production") tasks)

      (testing "without code management"
        (let [code-fn (fn [_ _ _] (throw (Exception. "Versioned code not supported.")))
              get-task-details (fn [env module task]
                                 (mc/task-details *jruby-service* *jruby-puppet* code-fn env nil module task))]
          (testing "when the environment exists"
            (testing "and the module exists"
              (testing "and the task exists"
                (testing "with metadata and payload files"
                  (let [expected-info {:metadata {:meta "data"}
                                       :name "apache::install_mods"
                                       :files [{:filename "install_mods.rb"
                                                :sha256 "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                                                :size_bytes 0
                                                :uri {:path "/puppet/v3/file_content/tasks/apache/install_mods.rb"
                                                      :params {:environment "production"}}}]}]
                    (get-task-details "production" "othermod" "init")
                    (is (= expected-info
                           (get-task-details "production" "apache" "install_mods")))))
                (testing "with metadata and library files"
                         (let [sorted (sort-by :filename
                                              [{:filename "files.rb",
                                                :sha256
                                                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                                                :size_bytes 0,
                                                :uri
                                                {:path "/puppet/v3/file_content/tasks/apache/files.rb",
                                                 :params {:environment "production"}}}
                                              {:filename "apache/lib/puppet/ruby_file.rb",
                                                :sha256
                                                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                                                :size_bytes 0,
                                                :uri
                                                {:path "/puppet/v3/file_content/plugins/puppet/ruby_file.rb",
                                                 :params {:environment "production"}}}
                                               {:filename "othermod/files/impl_helper.rb",
                                                :sha256
                                                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                                                :size_bytes 0,
                                                :uri
                                                {:path
                                                 "/puppet/v3/file_content/modules/othermod/impl_helper.rb",
                                                 :params {:environment "production"}}}
                                               {:filename "othermod/files/helper.rb",
                                                :sha256
                                                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                                                :size_bytes 0,
                                                :uri
                                                {:path "/puppet/v3/file_content/modules/othermod/helper.rb",
                                                 :params {:environment "production"}}}
                                               {:filename "othermod/files/morefiles/temp.rb",
                                                :sha256
                                                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                                                :size_bytes 0,
                                                :uri
                                                {:path "/puppet/v3/file_content/modules/othermod/morefiles/temp.rb",
                                                 :params {:environment "production"}}}])
                               expected-info {:metadata
                                              {:files (seq ["othermod/files/helper.rb" "othermod/files/morefiles/" "apache/lib/puppet/ruby_file.rb"]),
                                               :implementations
                                               (seq [{:files (seq ["othermod/files/impl_helper.rb"]), :name "files.rb"}])},
                                              :name "apache::files",
                                              :files sorted}
                               actual-info (update (get-task-details "production" "apache" "files") :files (partial sort-by :filename))]
                    (is (= (keys expected-info) (keys actual-info)))
                    (is (= (:metadata expected-info) (:metadata actual-info)))
                    (is (= sorted (:files actual-info)))
                    (doall (map (fn [expected actual] (is (= expected actual))) sorted (:files actual-info)))))
                (testing "without a metadata file"
                  (let [expected-info {:metadata {}
                                       :name "apache"
                                       :files [{:filename "init.rb"
                                                :sha256 "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                                                :size_bytes 0
                                                :uri {:path "/puppet/v3/file_content/tasks/apache/init.rb"
                                                      :params {:environment "production"}}}]}]
                    (is (= expected-info
                           (get-task-details "production" "apache" "init")))))

                (testing "with no payload files"
                  (let [expected-info {:metadata {:meta "data"}
                                       :name "apache::about"
                                       :files []}]
                    (is (thrown+? [:kind "puppet.tasks/no-implementation"] (get-task-details "production" "apache" "about"))))))

              (testing "but the task doesn't exist"
                (is (thrown-with-msg? RaiseException
                                      #"(TaskNotFound)"
                                      (get-task-details "production" "apache" "refuel")))))

            (testing "but the module doesn't exist"
              (is (thrown-with-msg? RaiseException
                                    #"(MissingModule)"
                                    (get-task-details "production" "mahjoule" "heat")))))

          (testing "when the environment doesn't exist"
            (is (thrown-with-msg? RaiseException
                                  #"(EnvironmentNotFound)"
                                  (get-task-details "DNE" "module" "missing"))))))

      (testing "with code management"
        (let [get-task-details (fn [env module task code-fn code-id]
                                 (mc/task-details *jruby-service* *jruby-puppet* code-fn env code-id module task))]
          (testing "uses static-file-content endpoint when code is available"
            (let [code-fn (fn [_ _ _] (ByteArrayInputStream. (.getBytes "" "UTF-8")))
                  expected-info {:metadata {:meta "data"}
                                 :name "apache::install_mods"
                                 :files [{:filename "install_mods.rb"
                                          :sha256 "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                                          :size_bytes 0
                                          :uri {:path "/puppet/v3/static_file_content/modules/apache/tasks/install_mods.rb"
                                                :params {:environment "production" :code_id "code-id"}}}]}]
              (is (= expected-info
                     (get-task-details "production" "apache" "install_mods" code-fn "code-id")))))
          (testing "uses file-content endpoint when code content differs from content reported by Puppet"
            (let [code-fn (fn [_ _ _] (ByteArrayInputStream. (.getBytes "some script" "UTF-8")))
                  expected-info {:metadata {:meta "data"}
                                 :name "apache::install_mods"
                                 :files [{:filename "install_mods.rb"
                                          :sha256 "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                                          :size_bytes 0
                                          :uri {:path "/puppet/v3/file_content/tasks/apache/install_mods.rb"
                                                :params {:environment "production"}}}]}]
              (is (= expected-info
                     (get-task-details "production" "apache" "install_mods" code-fn "code-id")))))
          (testing "uses file-content endpoint when code is unavailable"
            (let [code-fn (fn [_ _ _] (throw (Exception. "Versioned code not supported.")))
                  expected-info {:metadata {:meta "data"}
                                 :name "apache::install_mods"
                                 :files [{:filename "install_mods.rb"
                                          :sha256 "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                                          :size_bytes 0
                                          :uri {:path "/puppet/v3/file_content/tasks/apache/install_mods.rb"
                                                :params {:environment "production"}}}]}]
              (is (= expected-info
                     (get-task-details "production" "apache" "install_mods" code-fn "code-id"))))))))))
