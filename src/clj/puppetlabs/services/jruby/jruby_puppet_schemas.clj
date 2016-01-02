(ns puppetlabs.services.jruby.jruby-puppet-schemas
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env])
  (:import (clojure.lang Atom Agent IFn PersistentArrayMap PersistentHashMap)
           (com.puppetlabs.puppetserver PuppetProfiler JRubyPuppet EnvironmentRegistry)
           (com.puppetlabs.puppetserver.pool LockablePool)
           (org.jruby Main Main$Status RubyInstanceConfig)
           (org.jruby.embed ScriptingContainer)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def pool-queue-type
  "The Java datastructure type used to store JRubyPuppet instances which are
  free to be borrowed."
  LockablePool)

(defrecord PoisonPill
  ;; A sentinel object to put into a pool in case an error occurs while we're trying
  ;; to populate it.  This can be used by the `borrow` functions to detect error
  ;; state in a thread-safe manner.
  [err])

(defrecord RetryPoisonPill
  ;; A sentinel object to put into an old pool when we swap in a new pool.
  ;; This can be used to build `borrow` functionality that will detect the
  ;; case where we're trying to borrow from an old pool, so that we can retry
  ;; with the new pool.
  [pool])

(def JRubyPuppetConfig
  "Schema defining the config map for the JRubyPuppet pooling functions.

  The keys should have the following values:

    * :ruby-load-path - a vector of file paths, containing the locations of puppet source code.

    * :gem-home - The location that JRuby gems are stored

    * :master-conf-dir - file path to puppetmaster's conf dir;
        if not specified, will use the puppet default.

    * :master-code-dir - file path to puppetmaster's code dir;
        if not specified, will use the puppet default.

    * :master-var-dir - path to the puppetmaster's var dir;
        if not specified, will use the puppet default.

    * :master-run-dir - path to the puppetmaster's run dir;
        if not specified, will use the puppet default.

    * :master-log-dir - path to the puppetmaster's log dir;
        if not specified, will use the puppet default.

    * :max-active-instances - The maximum number of JRubyPuppet instances that
        will be pooled.

    * :http-client-ssl-protocols - A list of legal SSL protocols that may be used
        when https client requests are made.

    * :http-client-cipher-suites - A list of legal SSL cipher suites that may
        be used when https client requests are made.

    * :http-client-connect-timeout-milliseconds - The amount of time, in
        milliseconds, that an outbound HTTP connection will wait to connect
        before giving up.  If 0, the timeout is infinite and if negative, the
        value is undefined in the application and governed by the system default
        behavior.

    * :http-client-idle-timeout-milliseconds - The amount of time, in
        milliseconds, that an outbound HTTP connection will wait for data to be
        available after a request is sent before closing the socket.  If 0, the
        timeout is infinite and if negative, the value is undefined by the
        application and is governed by the default system behavior.

    * :use-legacy-auth-conf - Whether to use the legacy core Puppet auth.conf
        (true) or trapperkeeper-authorization (false) to authorize requests
        being made to core Puppet endpoints."
  ;; NOTE: there is a bug in the version of schema we're using, which causes
  ;; the order of things that you put into a `both` to be very important.
  ;; The `vector?` pred here MUST come before the `[schema/Str]`.  For more info
  ;; see https://github.com/Prismatic/schema/issues/68
  {:ruby-load-path              (schema/both (schema/pred vector?) [schema/Str])
   :gem-home                    schema/Str
   :master-conf-dir             (schema/maybe schema/Str)
   :master-code-dir             (schema/maybe schema/Str)
   :master-var-dir              (schema/maybe schema/Str)
   :master-run-dir              (schema/maybe schema/Str)
   :master-log-dir              (schema/maybe schema/Str)
   :http-client-ssl-protocols   [schema/Str]
   :http-client-cipher-suites   [schema/Str]
   :http-client-connect-timeout-milliseconds schema/Int
   :http-client-idle-timeout-milliseconds    schema/Int
   :borrow-timeout              schema/Int
   :max-active-instances        schema/Int
   :max-requests-per-instance   schema/Int
   :use-legacy-auth-conf        schema/Bool})

(def JRubyPoolAgent
  "An agent configured for use in managing JRuby pools"
  (schema/both Agent
               (schema/pred
                 (fn [a]
                   (let [state @a]
                     (and
                       (map? state)
                       (ifn? (:shutdown-on-error state))))))))

(def PoolState
  "A map that describes all attributes of a particular JRubyPuppet pool."
  {:pool         pool-queue-type
   :size schema/Int})

(def PoolStateContainer
  "An atom containing the current state of all of the JRubyPuppet pool."
  (schema/pred #(and (instance? Atom %)
                     (nil? (schema/check PoolState @%)))
               'PoolStateContainer))

(def PoolContext
  "The data structure that stores all JRubyPuppet pools and the original configuration."
  {:config                JRubyPuppetConfig
   :profiler              (schema/maybe PuppetProfiler)
   :pool-agent            JRubyPoolAgent
   :flush-instance-agent  JRubyPoolAgent
   :pool-state            PoolStateContainer})

(def JRubyInstanceState
  "State metadata for an individual JRubyPuppet instance"
  {:borrow-count schema/Int})

(def JRubyInstanceStateContainer
  "An atom containing the current state of a given JRubyPuppet instance."
  (schema/pred #(and (instance? Atom %)
                     (nil? (schema/check JRubyInstanceState @%)))
               'JRubyInstanceState))

;; A record representing an individual entry in the JRubyPuppet pool.
(schema/defrecord JRubyPuppetInstance
                  [pool :- pool-queue-type
                   id :- schema/Int
                   max-requests :- schema/Int
                   flush-instance-fn :- IFn
                   state :- JRubyInstanceStateContainer
                   jruby-puppet :- JRubyPuppet
                   scripting-container :- ScriptingContainer
                   environment-registry :- (schema/both
                                             EnvironmentRegistry
                                             (schema/pred
                                               #(satisfies? puppet-env/EnvironmentStateContainer %)))]
                  Object
                  (toString [this] (format "%s@%s {:id %s :state (Atom: %s)}"
                                           (.getName JRubyPuppetInstance)
                                           (Integer/toHexString (.hashCode this))
                                           id
                                           @state)))

(defn jruby-puppet-instance?
  [x]
  (instance? JRubyPuppetInstance x))

(defn jruby-main-instance?
  [x]
  (instance? Main x))

(defn jruby-main-status-instance?
  [x]
  (instance? Main$Status x))

(defn jruby-scripting-container?
  [x]
  (instance? ScriptingContainer x))

(defn jruby-instance-config?
  [x]
  (instance? RubyInstanceConfig x))

(defn poison-pill?
  [x]
  (instance? PoisonPill x))

(defn retry-poison-pill?
  [x]
  (instance? RetryPoisonPill x))

(def JRubyPuppetInstanceOrRetry
  (schema/conditional
    jruby-puppet-instance? (schema/pred jruby-puppet-instance?)
    retry-poison-pill? (schema/pred retry-poison-pill?)))

(def JRubyPuppetBorrowResult
  (schema/pred (some-fn nil?
                        retry-poison-pill?
                        jruby-puppet-instance?)))

(def JRubyMain
  (schema/pred jruby-main-instance?))

(def JRubyMainStatus
  (schema/pred jruby-main-status-instance?))

(def ConfigurableJRuby
  ;; This schema is a bit weird.  We have some common configuration that we need
  ;; to apply to two different kinds of JRuby objects: `ScriptingContainer` and
  ;; `JRubyInstanceConfig`.  These classes both have the same signatures for
  ;; all of the setter methods that we need to call on them (see
  ;; `jruby-internal/init-jruby-config`), but unfortunately the JRuby API doesn't
  ;; define an interface for those methods.  So, rather than duplicating the logic
  ;; in multiple places in the code, we use this (gross) schema to enforce that
  ;; an object must be an instance of one of those two types.
  (schema/conditional
   jruby-scripting-container? (schema/pred jruby-scripting-container?)
   jruby-instance-config? (schema/pred jruby-instance-config?)))

(def EnvMap
  "System Environment variables have strings for the keys and values of a map"
  {schema/Str schema/Str})

(def EnvPersistentMap
  "Schema for a clojure persistent map for the system environment"
  (schema/both EnvMap
    (schema/either PersistentArrayMap PersistentHashMap)))

(defn event-type-requested?
  [e]
  (= :instance-requested (:type e)))

(defn event-type-borrowed?
  [e]
  (= :instance-borrowed (:type e)))

(defn event-type-returned?
  [e]
  (= :instance-returned (:type e)))

(defn event-type-lock-requested?
  [e]
  (= :lock-requested (:type e)))

(defn event-type-lock-acquired?
  [e]
  (= :lock-acquired (:type e)))

(defn event-type-lock-released?
  [e]
  (= :lock-released (:type e)))

(def JRubyEventReason
  schema/Any)

(def JRubyRequestedEvent
  {:type (schema/eq :instance-requested)
   :reason JRubyEventReason})

(def JRubyBorrowedEvent
  {:type (schema/eq :instance-borrowed)
   :reason JRubyEventReason
   :requested-event JRubyRequestedEvent
   :instance JRubyPuppetBorrowResult})

(def JRubyReturnedEvent
  {:type (schema/eq :instance-returned)
   :reason JRubyEventReason
   :instance JRubyPuppetInstanceOrRetry})

(def JRubyLockRequestedEvent
  {:type (schema/eq :lock-requested)
   :reason JRubyEventReason})

(def JRubyLockAcquiredEvent
  {:type (schema/eq :lock-acquired)
   :reason JRubyEventReason})

(def JRubyLockReleasedEvent
  {:type (schema/eq :lock-released)
   :reason JRubyEventReason})

(def JRubyEvent
  (schema/conditional
    event-type-requested? JRubyRequestedEvent
    event-type-borrowed? JRubyBorrowedEvent
    event-type-returned? JRubyReturnedEvent
    event-type-lock-requested? JRubyLockRequestedEvent
    event-type-lock-acquired? JRubyLockAcquiredEvent
    event-type-lock-released? JRubyLockReleasedEvent))
