(ns ^:figwheel-no-load puppetlabs.puppetserver.dashboard.dev
  (:require [puppetlabs.puppetserver.dashboard :as dashboard]
            [figwheel.client :as figwheel :include-macros true]
            [reagent.core :as r]))

(enable-console-print!)

(figwheel/watch-and-reload
  :websocket-url "ws://localhost:3449/figwheel-ws"
  :jsload-callback (fn [] (r/force-update-all)))

(dashboard/init!)
