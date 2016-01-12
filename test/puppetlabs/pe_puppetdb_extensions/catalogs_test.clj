(ns puppetlabs.pe-puppetdb-extensions.catalogs-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.testutils.services :refer [get-json]]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [blocking-command-post with-ext-instances]]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
            [clojure.walk :refer [keywordize-keys]]
            [clj-time.core :refer [days ago now]]
            [clj-time.coerce :refer [to-timestamp]]
            [puppetlabs.puppetdb.reports :as reports]))

(defn munge-child-data [catalogs]
  (map (fn [catalog]
         (-> catalog
             (update :resources :data)
             (update :edges :data)))
       catalogs))

(deftest query-resources-on-reports
  (with-ext-instances [pdb (utils/sync-config nil)]
    (let [timestamps [(now) (-> 1 days ago) (-> 2 days ago)]
          certname "foo.local"
          example-catalog (-> (get-in wire-catalogs [7 :basic])
                              (assoc :certname certname))
          example-report (-> (:basic reports)
                             (assoc :certname certname)
                             reports/report-query->wire-v6)]
      (doseq [timestamp timestamps
              :let [tx-uuid (ks/uuid)]]
        (->> (assoc example-report
                    :transaction_uuid tx-uuid
                    :producer_timestamp timestamp)
             (blocking-command-post (utils/pdb-cmd-url) "store report" 6))
        (->> (assoc example-catalog
                    :transaction_uuid tx-uuid
                    :producer_timestamp timestamp)
             (blocking-command-post (utils/pdb-cmd-url) "replace catalog" 7)))
      (testing "historical catalogs views have the right amount of data"
       (let [historical-catalogs (get-json (utils/pe-pdb-url) "/historical-catalogs")
             resource-graphs (get-json (utils/pe-pdb-url) "/resource-graphs")]
         (is (= (count timestamps)
                (count historical-catalogs)
                (count resource-graphs)))
         (is (not (nil? (:edges (first resource-graphs)))))
         (is (not (nil? (:resources (first resource-graphs)))))))

      (testing "paging options and queries work per usual"
        (let [[resource-graph :as resource-graphs]
              (get-json (utils/pe-pdb-url) "/resource-graphs"
                        {:query-params
                         {:query (json/generate-string [:= :certname certname])
                          :limit 1
                          :order_by (json/generate-string
                                     [{:field :producer_timestamp :order :desc}])}})]
          (is (= 1 (count resource-graphs)))
          (is (= (to-timestamp (first timestamps))
                 (to-timestamp (:producer_timestamp resource-graph))))))

      (testing "historical catalogs matches catalogs endpoint"
        (let [historical-catalogs (get-json (utils/pe-pdb-url) "/historical-catalogs"
                                            {:query-params
                                             {:query (json/generate-string [:= :certname certname])
                                              :limit 1
                                              :order_by (json/generate-string
                                                         [{:field :producer_timestamp :order :desc}])}})
              normal-catalogs (get-json (utils/pdb-query-url) "/catalogs"
                                        {:query-params
                                         {:query (json/generate-string [:= :certname certname])}})]
          (is (= (map #(dissoc % :edges :resources) historical-catalogs)
                 (map #(dissoc % :edges :resources) normal-catalogs)))
          (is (= (sort-by (juxt :source_title :target_title)
                          (:edges (first historical-catalogs)))
                 (sort-by (juxt :source_title :target_title)
                          (get-in (first normal-catalogs) [:edges :data]))))
          (is (= (sort-by (juxt :type :title)
                          (:resources (first historical-catalogs)))
                 (sort-by (juxt :type :title)
                          (map #(dissoc % :resource)
                               (get-in (first normal-catalogs) [:resources :data])))))))

      (testing "when data is missing"
        (testing "when there is no report for a catalog"
          (->> (assoc example-catalog
                      :transaction_uuid (ks/uuid)
                      :certname "bar.example.com"
                      :producer_timestamp (-> 3 days ago))
               (blocking-command-post (utils/pdb-cmd-url) "replace catalog" 7))

          (let [resource-graphs
                (get-json (utils/pe-pdb-url) "/resource-graphs"
                          {:query-params
                           {:query (json/generate-string [:= :certname "bar.example.com"])}})]
            (is (empty? resource-graphs))))

        (testing "when there is no catalog for a report"
          (->> (assoc example-report
                      :transaction_uuid (ks/uuid)
                      :certname "baz.lan"
                      :producer_timestamp (-> 3 days ago))
               (blocking-command-post (utils/pdb-cmd-url) "store report" 6))

          (let [resource-graphs
                (get-json (utils/pe-pdb-url) "/resource-graphs"
                          {:query-params
                           {:query (json/generate-string [:= :certname "baz.lan"])}})]
            (is (= 1 (count resource-graphs)))
            (is (not (nil? (:resources (first resource-graphs)))))
            ;; parameters and edges come from the catalog
            (is (empty? (map :parameters (:resources (first resource-graphs)))))
            (is (empty? (:edges (first resource-graphs))))))))))