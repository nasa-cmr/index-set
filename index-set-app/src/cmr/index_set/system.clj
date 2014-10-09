(ns cmr.index-set.system
  "Defines functions for creating, starting, and stopping the application. Applications are
  represented as a map of components. Design based on
  http://stuartsierra.com/2013/09/15/lifecycle-composition and related posts."
  (:require [cmr.common.lifecycle :as lifecycle]
            [cmr.common.log :as log :refer (debug info warn error)]
            [cmr.index-set.api.routes :as routes]
            [cmr.common.api.web-server :as web]
            [cmr.system-trace.context :as context]
            [cmr.index-set.data.elasticsearch :as es]
            [cmr.elastic-utils.config :as es-config]
            [cmr.transmit.config :as transmit-config]
            [cmr.common.config :as cfg]
            [cmr.acl.core :as acl]))

(def app-port (cfg/config-value-fn :index-set-port 3005 #(Long. %)))

(def
  ^{:doc "Defines the order to start the components."
    :private true}
  component-order [:log :index :web])

(def relative-root-url
  "Defines a root path that will appear on all requests sent to this application. For example if
  the relative-root-url is '/cmr-app' and the path for a URL is '/foo' then the full url would be
  http://host:port/cmr-app/foo. This should be set when this application is deployed in an
  environment where it is accessed through a VIP."
  (cfg/config-value-fn :index-set-relative-root-url ""))

(defn create-system
  "Returns a new instance of the whole application."
  []
  (let [sys {:log (log/create-logger)
             :index (es/create-elasticsearch-store (es-config/elastic-config))
             :web (web/create-web-server (app-port) routes/make-api)
             :caches {acl/token-imp-cache-key (acl/create-token-imp-cache)}
             :zipkin (context/zipkin-config "index-set" false)
             :relative-root-url (relative-root-url)}]
    (transmit-config/system-with-connections sys [:echo-rest])))

(defn start
  "Performs side effects to initialize the system, acquire resources,
  and start it running. Returns an updated instance of the system."
  [this]
  (info "index-set System starting")
  (let [started-system (reduce (fn [system component-name]
                                 (update-in system [component-name]
                                            #(lifecycle/start % system)))
                               this
                               component-order)]
    (info "index-set System started")
    started-system))


(defn stop
  "Performs side effects to shut down the system and release its
  resources. Returns an updated instance of the system."
  [this]
  (info "index-set System shutting down")
  (let [stopped-system (reduce (fn [system component-name]
                                 (update-in system [component-name]
                                            #(lifecycle/stop % system)))
                               this
                               (reverse component-order))]
    (info "index-set System stopped")
    stopped-system))
