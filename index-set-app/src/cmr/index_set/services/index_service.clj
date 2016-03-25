(ns cmr.index-set.services.index-service
  "Provide functions to store, retrieve, delete index-sets"
  (:require [clojure.string :as s]
            [cmr.common.log :as log :refer (debug info warn error)]
            [cmr.index-set.data.elasticsearch :as es]
            [cmr.elastic-utils.connect :as es-util]
            [cheshire.core :as json]
            [camel-snake-kebab.core :as csk]
            [cmr.acl.core :as acl]
            [cmr.common.util :as util]
            [cmr.common.services.errors :as errors]
            [cmr.transmit.echo.rest :as echo-rest]
            [cmr.index-set.services.messages :as m]
            [clojure.walk :as walk]
            [cheshire.core :as cheshire]
            [cmr.index-set.config.elasticsearch-config :as es-config])
  (:import clojure.lang.ExceptionInfo))

;; configured list of cmr concepts
(def concept-types [:collection :granule :tag])

(defn context->es-store
  [context]
  (get-in context [:system :index]))

(defn gen-valid-index-name
  "Join parts, lowercase letters and change '-' to '_'."
  [prefix-id suffix]
  (s/lower-case (s/replace (format "%s_%s" prefix-id suffix) #"-" "_")))

(defn- build-indices-list-w-config
  "Given an index-set, build list of indices with config."
  [idx-set]
  (let [prefix-id (get-in idx-set [:index-set :id])]
    (for [concept-type concept-types
          idx (get-in idx-set [:index-set concept-type :indexes])]
      (let [mapping (get-in idx-set [:index-set concept-type :mapping])
            {idx-name :name settings :settings} idx]
        {:index-name (gen-valid-index-name prefix-id idx-name)
         :settings settings
         :mapping mapping}))))

(defn get-index-names
  "Given a index set build list of index names."
  [idx-set]
  (let [prefix-id (get-in idx-set [:index-set :id])]
    (for [concept-type concept-types
          idx (get-in idx-set [:index-set concept-type :indexes])]
      (gen-valid-index-name prefix-id (:name idx)))))

(defn given-index-names->es-index-names
  "Map given names with generated elastic index names."
  [index-names-array prefix-id]
  (apply merge
         (for [index-name index-names-array]
           {(keyword index-name) (gen-valid-index-name prefix-id index-name)})))

(defn prune-index-set
  "Returns the index set with only the id, name, and a map of concept types to the index name map."
  [index-set]
  (let [prefix (:id index-set)]
    {:id (:id index-set)
     :name (:name index-set)
     :concepts (into {} (for [concept-type concept-types]
                          [concept-type
                           (into {} (for [idx (get-in index-set [concept-type :indexes])]
                                      [(keyword (:name idx)) (gen-valid-index-name prefix (:name idx))]))]))}))

(defn get-index-sets
  "Fetch all index-sets in elastic."
  [context]
  (let [{:keys [index-name mapping]} es-config/idx-cfg-for-index-sets
        idx-mapping-type (first (keys mapping))
        index-sets (es/get-index-sets (context->es-store context) index-name idx-mapping-type)]
    (map #(select-keys (:index-set %) [:id :name :concepts])
         index-sets)))

(defn index-set-exists?
  "Check index-set existsence"
  [context index-set-id]
  (let [{:keys [index-name mapping]} es-config/idx-cfg-for-index-sets
        idx-mapping-type (first (keys mapping))]
    (es/index-set-exists? (context->es-store context) index-name idx-mapping-type index-set-id)))

(defn get-index-set
  "Fetch index-set associated with an index-set id."
  [context index-set-id]
  (let [{:keys [index-name mapping]} es-config/idx-cfg-for-index-sets
        idx-mapping-type (first (keys mapping))]
    (es/get-index-set (context->es-store context) index-name idx-mapping-type index-set-id)))

(defn index-set-id-validation
  "Verify id is a positive integer."
  [index-set]
  (let [index-set-id (get-in index-set [:index-set :id])
        json-index-set-str (json/generate-string index-set)]
    (when-not (and (integer? index-set-id) (> index-set-id 0))
      (m/invalid-id-msg index-set-id json-index-set-str))))

(defn id-name-existence-check
  "Check for index-set id and name."
  [index-set]
  (let [index-set-id (get-in index-set [:index-set :id])
        index-set-name (get-in index-set [:index-set :name])
        json-index-set-str (json/generate-string index-set)]
    (when-not (and index-set-id index-set-name)
      (m/missing-id-name-msg json-index-set-str))))

(defn index-cfg-validation
  "Verify if required elements are present to create an elastic index."
  [index-set]
  (let [indices-w-config (build-indices-list-w-config index-set)
        json-index-set-str (json/generate-string index-set)]
    (when-not (every? true? (map #(and (boolean (% :index-name))
                                       (boolean (% :settings)) (boolean (% :mapping))) indices-w-config))
      (m/missing-idx-cfg-msg json-index-set-str))))

(defn index-set-existence-check
  "Check index-set existence"
  [context index-set]
  (let [index-set-id (get-in index-set [:index-set :id])
        {:keys [index-name mapping]} es-config/idx-cfg-for-index-sets
        idx-mapping-type (first (keys mapping))]
    (when (es/index-set-exists? (context->es-store context) index-name idx-mapping-type index-set-id)
      (m/index-set-exists-msg index-set-id))))


(defn validate-requested-index-set
  "Verify input index-set is valid."
  [context index-set allow-update?]

  (when-not allow-update?
    (when-let [error (index-set-existence-check context index-set)]
      (errors/throw-service-error :conflict error))
    (when-let [error (id-name-existence-check index-set)]
      (errors/throw-service-error :invalid-data error)))

  (when-let [error (index-set-id-validation index-set)]
    (errors/throw-service-error :invalid-data error))
  (when-let [error (index-cfg-validation index-set)]
    (errors/throw-service-error :invalid-data error)))

(defn index-requested-index-set
  "Index requested index-set along with generated elastic index names"
  [context index-set]
  (let [index-set-w-es-index-names (assoc-in index-set [:index-set :concepts]
                                             (:concepts (prune-index-set (:index-set index-set))))
        encoded-index-set-w-es-index-names (-> index-set-w-es-index-names
                                               json/generate-string
                                               util/string->gzip-base64)
        es-doc {:index-set-id (get-in index-set [:index-set :id])
                :index-set-name (get-in index-set [:index-set :name])
                :index-set-request encoded-index-set-w-es-index-names}
        doc-id (str (:index-set-id es-doc))
        {:keys [index-name mapping]} es-config/idx-cfg-for-index-sets
        idx-mapping-type (first (keys mapping))]
    (es/save-document-in-elastic context index-name idx-mapping-type doc-id es-doc)))

(defn create-index-set
  "Create indices listed in index-set. Rollback occurs if indices creation or index-set doc indexing fails."
  [context index-set]
  (validate-requested-index-set context index-set false)
  (let [index-names (get-index-names index-set)
        indices-w-config (build-indices-list-w-config index-set)
        es-cfg (-> context :system :index :config)
        idx-name-of-index-sets (:index-name es-config/idx-cfg-for-index-sets)
        es-store (context->es-store context)]

    ;; rollback index-set creation if index creation fails
    (try
      (dorun (map #(es/create-index es-store %) indices-w-config))
      (catch ExceptionInfo e
        (dorun (map #(es/delete-index es-store %) index-names))
        (m/handle-elastic-exception "attempt to create indices of index-set failed" e)))
    (try
      (index-requested-index-set context index-set)
      (catch ExceptionInfo e
        (dorun (map #(es/delete-index es-store %) index-names))
        (m/handle-elastic-exception "attempt to index index-set doc failed"  e)))))

(defn update-index-set
  "Updates indices in the index set"
  [context index-set]
  (info "Updating index-set" (pr-str index-set))
  (validate-requested-index-set context index-set true)
  (let [index-names (get-index-names index-set)
        indices-w-config (build-indices-list-w-config index-set)
        es-cfg (-> context :system :index :config)
        idx-name-of-index-sets (:index-name es-config/idx-cfg-for-index-sets)
        es-store (context->es-store context)]

    (doseq [idx indices-w-config]
      (es/update-index es-store idx))

    (index-requested-index-set context index-set)))

(defn delete-index-set
  "Delete all indices having 'id_' as the prefix in the elastic, followed by index-set doc delete"
  [context index-set-id]
  (let [index-names (get-index-names (get-index-set context index-set-id))
        {:keys [index-name mapping]} es-config/idx-cfg-for-index-sets
        idx-mapping-type (first (keys mapping))]
    (dorun (map #(es/delete-index (context->es-store context) %) index-names))
    (es/delete-document context index-name idx-mapping-type index-set-id)))

(defn- add-rebalancing-collection
  "Adds a new rebalancing collections to the set of rebalancing collections."
  [rebalancing-colls concept-id]
  (if rebalancing-colls
    (if (contains? (set rebalancing-colls) concept-id)
      (errors/throw-service-error
       :bad-request
       (format "The index set already contains rebalancing collection [%s]" concept-id))
      (conj rebalancing-colls concept-id))
    #{concept-id}))

(defn- remove-rebalancing-collection
  "Removes a rebalancing collections from the set of rebalancing collections."
  [rebalancing-colls concept-id]
  (let [rebalancing-colls-set (set rebalancing-colls)]
    (if (contains? rebalancing-colls-set concept-id)
      (seq (disj rebalancing-colls-set concept-id))
      (errors/throw-service-error
       :bad-request
       (format "The index set does not contain the rebalancing collection [%s]" concept-id)))))

(defn- add-new-granule-index
  "Adds a new granule index for the given collection. Validates the collection does not already have
   an index."
  [index-set collection-concept-id]
  (let [existing-index-names (->> (get-in index-set [:index-set :granule :indexes]) (map :name) set)
        _ (when (contains? existing-index-names collection-concept-id)
            (errors/throw-service-error
             :bad-request
             (format "The collection [%s] already has a separate granule index" collection-concept-id)))
        individual-index-settings (get-in index-set [:index-set :granule :individual-index-settings])]
    (update-in index-set [:index-set :granule :indexes]
               conj
               {:name collection-concept-id
                :settings individual-index-settings})))

(defn mark-collection-as-rebalancing
  "Marks the given collection as rebalancing in the index set."
  [context index-set-id concept-id]
  (let [index-set (get-index-set context index-set-id)
        ;; Add the collection to the list of rebalancing collections. Also does validation.
        index-set (update-in index-set [:index-set :granule :rebalancing-collections]
                   add-rebalancing-collection concept-id)
        index-set (add-new-granule-index index-set concept-id)]

    ;; Update the index set. This will create the new collection indexes as needed.
    (update-index-set context index-set)))

(defn finalize-collection-rebalancing
  "Removes the collection from the list of rebalancing collections"
  [context index-set-id concept-id]
  (let [index-set (get-index-set context index-set-id)
        ;; Remove the collection from the list of rebalancing collections. Also does validation.
        index-set (update-in index-set [:index-set :granule :rebalancing-collections]
                   remove-rebalancing-collection concept-id)]
    (update-index-set context index-set)))

(defn reset
  "Put elastic in a clean state after deleting indices associated with index-sets and index-set docs."
  [context]
  (let [{:keys [index-name mapping]} es-config/idx-cfg-for-index-sets
        idx-mapping-type (first (keys mapping))
        index-set-ids (map #(first %) (es/get-index-set-ids (context->es-store context) index-name idx-mapping-type))]
    ;; delete indices assoc with index-set
    (doseq [id index-set-ids]
      (delete-index-set context (str id)))))

(defn health
  "Returns the health state of the app."
  [context]
  (let [elastic-health (es-util/health context :index)
        echo-rest-health (echo-rest/health context)
        ok? (and (:ok? elastic-health) (:ok? echo-rest-health))]
    {:ok? ok?
     :dependencies {:elastic_search elastic-health
                    :echo echo-rest-health}}))


