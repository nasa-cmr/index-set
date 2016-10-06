(ns cmr.index-set.api.routes
  "Defines the HTTP URL routes for the application."
  (:require [compojure.route :as route]
            [compojure.core :refer :all]
            [ring.middleware.json :as ring-json]
            [ring.middleware.params :as params]
            [ring.middleware.nested-params :as nested-params]
            [ring.middleware.keyword-params :as keyword-params]
            [cheshire.core :as json]
            [ring.util.response :as r]
            [cmr.common.log :refer (debug info warn error)]
            [cmr.common.api.errors :as errors]
            [cmr.common.cache :as cache]
            [clojure.walk :as walk]
            [cheshire.core :as json]
            [cmr.index-set.services.index-service :as index-svc]
            [cmr.common.api.context :as context]
            [cmr.acl.core :as acl]
            [cmr.common-app.api.routes :as common-routes]
            [cmr.common-app.api.health :as common-health]))

(defn- build-routes [system]
  (routes
    (context (:relative-root-url system) []
      (context "/index-sets" []
        (POST "/" {body :body request-context :request-context params :params headers :headers}
          (let [index-set (walk/keywordize-keys body)]
            (acl/verify-ingest-management-permission request-context :update)
            (r/created (index-svc/create-index-set request-context index-set))))

        ;; respond with index-sets in elastic
        (GET "/" {request-context :request-context params :params headers :headers}
          (acl/verify-ingest-management-permission request-context :read)
          (r/response (index-svc/get-index-sets request-context)))

        (context "/:id" [id]
          (GET "/" {request-context :request-context params :params headers :headers}
            (acl/verify-ingest-management-permission request-context :read)
            (r/response (index-svc/get-index-set request-context id)))

          (PUT "/" {request-context :request-context body :body params :params headers :headers}
            (let [index-set (walk/keywordize-keys body)]
              (acl/verify-ingest-management-permission request-context :update)
              (index-svc/update-index-set request-context index-set)
              {:status 200}))

          (DELETE "/" {request-context :request-context params :params headers :headers}
            (acl/verify-ingest-management-permission request-context :update)
            (index-svc/delete-index-set request-context id)
            {:status 204})

          (context "/rebalancing-collections/:concept-id" [concept-id]

            ;; Marks the collection as rebalancing in the index set.
            (POST "/start" {request-context :request-context}
              (acl/verify-ingest-management-permission request-context :update)
              (index-svc/mark-collection-as-rebalancing request-context id concept-id)
              {:status 200})

            ;; Marks the collection as completed rebalancing
            (POST "/finalize" {request-context :request-context}
              (acl/verify-ingest-management-permission request-context :update)
              (index-svc/finalize-collection-rebalancing request-context id concept-id)
              {:status 200}))))


      ;; add routes for accessing caches
      common-routes/cache-api-routes

      ;; add routes for checking health of the application
      (common-health/health-api-routes index-svc/health)

      ;; delete all of the indices associated with index-sets and index-set docs in elastic
      (POST "/reset" {request-context :request-context params :params headers :headers}
        (acl/verify-ingest-management-permission request-context :update)
        (cache/reset-caches request-context)
        (index-svc/reset request-context)
        {:status 204}))

    (route/not-found "Not Found")))

(defn make-api [system]
  (-> (build-routes system)
      acl/add-authentication-handler
      errors/invalid-url-encoding-handler
      errors/exception-handler
      common-routes/add-request-id-response-handler
      (context/build-request-context-handler system)
      keyword-params/wrap-keyword-params
      nested-params/wrap-nested-params
      ring-json/wrap-json-body
      common-routes/pretty-print-response-handler
      params/wrap-params))
