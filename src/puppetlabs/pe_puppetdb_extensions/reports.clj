(ns puppetlabs.pe-puppetdb-extensions.reports
  (:require [puppetlabs.puppetdb.query.report-data :as report-data]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.query.reports :as query-reports]
            [puppetlabs.puppetdb.query-eng :as query-eng]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.query-eng.engine :as query-eng-engine]
            [puppetlabs.puppetdb.honeysql :as honeysql]
            [compojure.core :as compojure]
            [puppetlabs.puppetdb.scf.storage :as scf-storage]))

(defn reports-resources-routes
  [get-shared-globals]
  (-> (compojure/routes
       (compojure/GET "/query/v4/reports/:hash/resources" [hash]
                      (-> (fn [{:keys [globals]}]
                            (let [{db :scf-read-db url-prefix :url-prefix} globals
                                  query (json/generate-string ["=" "hash" hash])]
                              (query-eng/produce-streaming-body :report-resources :v4 query {} db url-prefix)))
                          (mid/wrap-with-parent-check :v4 :report hash))))
      mid/verify-accepts-json
      (mid/wrap-with-globals get-shared-globals)
      vector))

(defn report-resources-query
  "Query intended to be used by the `/reports/<hash>/reosurces` endpoint
  used for digging into the resources for a specific report."
  []
  (query-eng-engine/map->Query {:projections {"resources" {:type :json
                                          :queryable? false
                                          :field :reports.resources}
                             "hash" {:type :string
                                     :queryable? true
                                     :query-only? true
                                     :field (query-eng-engine/hsql-hash-as-str :reports.hash)}}
               :selection {:from [:reports]}

               :alias "resources"
               :subquery? false
               :entity :reports
               :source-table "reports"}))

(defn munge-result-rows
  [version url-prefix]
  (let [base-url (str url-prefix "/" (name version))]
    (fn [rows]
      (map (comp (query-reports/row->report base-url)
                 #(utils/update-when % [:resources] utils/child->expansion :reports :resources base-url))
           rows))))

(defn reports-with-resources-query []
  (-> (query-eng-engine/reports-query)
      (assoc-in [:projections "resources"]
                {:type :json
                 :queryable? false
                 :expandable? true
                 :field
                 {:select [(honeysql/row-to-json :t)]
                  :from [[{:select
                           [[:resources :data]
                            [(query-eng-engine/hsql-hash-as-str :hash) :href]]} :t]]}})))

(defn turn-on-unchanged-resources!
  []
  (reset! scf-storage/store-resources-column? true)
  (swap! query-eng/entity-fn-idx merge
         {:report-resources
          {:munge (report-data/munge-result-rows :resources)
           :rec report-resources-query}
          :reports {:munge munge-result-rows
                    :rec reports-with-resources-query}}))