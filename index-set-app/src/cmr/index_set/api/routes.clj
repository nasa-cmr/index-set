(ns cmr.index-set.api.routes
  "Defines the HTTP URL routes for the application."
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [compojure.core :refer :all]
            [ring.middleware.json :as ring-json]
            [cheshire.core :as json]
            [ring.util.response :as r]
            [cmr.common.log :refer (debug info warn error)]
            [cmr.common.api :as api]
            [cmr.common.api.errors :as errors]
            [cmr.common.cache :as cache]
            [clojure.walk :as walk]
            [cheshire.core :as json]
            [cmr.index-set.services.index-service :as index-svc]
            [cmr.system-trace.http :as http-trace]
            [cmr.acl.core :as acl]
            [cmr.common-app.api.routes :as common-routes]))

(defn- build-routes [system]
  (routes
    (context (:relative-root-url system) []
      (context "/index-sets" []
        (POST "/" {body :body request-context :request-context params :params headers :headers}
          (let [index-set (walk/keywordize-keys body)
                context (acl/add-authentication-to-context request-context params headers)]
            (acl/verify-ingest-management-permission context :update)
            (r/created (index-svc/create-index-set request-context index-set))))

        ;; respond with index-sets in elastic
        (GET "/" {request-context :request-context params :params headers :headers}
          (let [context (acl/add-authentication-to-context request-context params headers)]
            (acl/verify-ingest-management-permission context :read)
            (r/response (index-svc/get-index-sets request-context))))

        (context "/:id" [id]
          (GET "/" {request-context :request-context params :params headers :headers}
            (let [context (acl/add-authentication-to-context request-context params headers)]
              (acl/verify-ingest-management-permission context :read)
              (r/response (index-svc/get-index-set request-context id))))

          (PUT "/" {request-context :request-context body :body params :params headers :headers}
            (let [index-set (walk/keywordize-keys body)
                  context (acl/add-authentication-to-context request-context params headers)]
              (acl/verify-ingest-management-permission context :update)
              (index-svc/update-index-set request-context index-set)
              {:status 200}))

          (DELETE "/" {request-context :request-context params :params headers :headers}
            (let [context (acl/add-authentication-to-context request-context params headers)]
              (acl/verify-ingest-management-permission context :update)
              (index-svc/delete-index-set request-context id)
              {:status 204}))))

      ;; add routes for accessing caches
      common-routes/cache-api-routes

      ;; add routes for checking health of the application
      (common-routes/health-api-routes index-svc/health)

      ;; delete all of the indices associated with index-sets and index-set docs in elastic
      (POST "/reset" {request-context :request-context params :params headers :headers}
        (let [context (acl/add-authentication-to-context request-context params headers)]
          (acl/verify-ingest-management-permission context :update)
          (cache/reset-caches request-context)
          (index-svc/reset request-context)
          {:status 204})))

    (route/not-found "Not Found")))

(defn make-api [system]
  (-> (build-routes system)
      (http-trace/build-request-context-handler system)
      errors/invalid-url-encoding-handler
      errors/exception-handler
      handler/site
      ring-json/wrap-json-body
      ring-json/wrap-json-response))



