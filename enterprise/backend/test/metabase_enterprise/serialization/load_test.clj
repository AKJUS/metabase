(ns ^:mb/driver-tests metabase-enterprise.serialization.load-test
  (:refer-clojure :exclude [load])
  (:require
   [clojure.data :as data]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [metabase-enterprise.serialization.cmd :refer [v1-dump! v1-load!]]
   [metabase-enterprise.serialization.load :as load]
   [metabase-enterprise.serialization.test-util :as ts]
   [metabase.models.interface :as mi]
   [metabase.models.visualization-settings :as mb.viz]
   [metabase.query-processor :as qp]
   [metabase.query-processor.middleware.permissions :as qp.perms]
   [metabase.test :as mt]
   [metabase.test.data.users :as test.users]
   [metabase.test.fixtures :as fixtures]
   [metabase.util :as u]
   [metabase.util.i18n :refer [trs]]
   [metabase.util.log :as log]
   [toucan2.core :as t2])
  (:import
   (org.apache.commons.io FileUtils)))

(set! *warn-on-reflection* true)

(use-fixtures :once
  (fixtures/initialize :test-users-personal-collections))

(defn- delete-directory!
  [file-or-filename]
  (FileUtils/deleteDirectory (io/file file-or-filename)))

(def ^:private dump-dir "test-dump")

(defn- world-snapshot
  []
  (into {} (for [model [:model/Database :model/Table :model/Field :model/Segment :model/Collection :model/Dashboard :model/DashboardCard :model/Pulse
                        :model/Card :model/DashboardCardSeries :model/FieldValues :model/Dimension :model/PulseCard :model/PulseChannel :model/User
                        :model/NativeQuerySnippet]]
             [model (t2/select-fn-set :id model)])))

(defmacro with-world-cleanup
  [& body]
  `(let [snapshot# (world-snapshot)]
     (try
       ~@body
       (finally
         (doseq [[model# ids#] (second (data/diff snapshot# (world-snapshot)))]
           (some->> ids#
                    not-empty
                    (vector :in)
                    (t2/delete! model# :id)))))))

(defn- card-query-results [card]
  (let [query (:dataset_query card)]
    (binding [qp.perms/*card-id* nil]
      (-> query
          (assoc-in [:info :card-id] (:id card))
          qp/process-query))))

(defn- query-res-match
  "Checks that the queries for a card match between original (pre-dump) and new (after load). For now, just checks the
  native form, not actual data, since some of the statements don't have an ordering (thus the rows won't be stable)."
  [orig-results loaded-card]
  (let [card-name   (:name loaded-card)
        orig-result (get orig-results card-name)]
    (try
      (let [new-result  (card-query-results loaded-card)]
        (is (some? orig-result) (format "No original query result found for card: %s" card-name))
        (is (some? new-result) (format "No results produced for loaded card: %s" card-name))
        (is
         (apply = (map #(get-in % [:data :native_form :query]) [orig-result new-result]))
         "Native query form did not match"))
      (catch Exception e
        (let [msg (trs "Failed to execute query for loaded card \"{0}\": {1}" card-name (.getMessage e))]
          (log/error e msg)
          ;; TODO: figure out a better way to simply fail with an explicit message
          (is (nil? e) msg))))))

(defn- collection-name-for-card [card]
  (let [collection-id (:collection_id card)]
    (if (nil? collection-id)
      "root"
      (t2/select-one-fn :name :model/Collection :id (:collection_id card)))))

(defn- collection-names-match
  "Checks that the collection name for a card matches between original (pre-dump) and new (after load)."
  [collection-names loaded-card]
  (let [card-name (:name loaded-card)]
    (is (= (get collection-names card-name) (collection-name-for-card loaded-card)) "Collection name did not match")))

(defn- gather-orig-results
  "Create a map from card names to their query results"
  [card-ids]
  (reduce (fn [acc card-id]
            (let [card (t2/select-one :model/Card :id card-id)]
              (assoc acc (:name card) (card-query-results card))))
          {}
          card-ids))

(defn- gather-collections
  "Create a map from card names to their collection names"
  [card-ids]
  (reduce (fn [acc card-id]
            (let [card (t2/select-one :model/Card :id card-id)]
              (assoc acc (:name card) (collection-name-for-card card))))
          {}
          card-ids))

(defmulti ^:private assert-loaded-entity
  {:arglists '([instance fingerprint])}
  (fn [instance _fingerprint]
    (mi/model instance)))

(defn- test-loaded-card [card card-name]
  (when (= "My Nested Card" card-name)
    (testing "Visualization settings for a Card were persisted correctly"
      (let [vs (:visualization_settings card)
            col (-> (:column_settings vs)
                    first)
            [col-key col-val] col
            col-ref (mb.viz/parse-db-column-ref col-key)
            {:keys [::mb.viz/field-id]} col-ref
            [{col-name :name col-field-ref :fieldRef col-enabled :enabled :as _tbl-col} & _] (:table.columns vs)
            [_ col-field-id _] col-field-ref]
        (is (some? (:table.columns vs)))
        (is (some? (:column_settings vs)))
        (is (integer? field-id))
        (is (= "latitude" (-> (t2/select-one-fn :name :model/Field :id field-id)
                              u/lower-case-en)))
        (is (= {:show_mini_bar true
                :column_title "Parallel"} col-val))
        (is (= "Venue Category" col-name))
        (is (true? col-enabled))
        (is (integer? col-field-id) "fieldRef within table.columns was properly serialized and loaded")
        (is (= "category_id" (-> (t2/select-one-fn :name :model/Field :id col-field-id)
                                 u/lower-case-en)))))))

(defn- test-pivot-card [card card-name]
  (when (= "Pivot Table Card" card-name)
    (testing "Visualization settings for a Card were persisted correctly"
      (let [vs      (:visualization_settings card)
            pivot   (:pivot_table.column_split vs)
            vecs    (concat (:columns pivot) (:rows pivot))]
        (is (some? vecs))
        (doseq [column-name vecs]
          (is (string? column-name) "column names within pivot table was properly serialized and loaded"))))))

(defmethod assert-loaded-entity :model/Card
  [{card-name :name :as card} {:keys [query-results collections]}]
  (testing (format "Card: %s" card-name)
    (query-res-match query-results card)
    (collection-names-match collections card)
    (test-loaded-card card card-name)
    (test-pivot-card card card-name)
    card))

(defn- collection-parent-name [collection]
  (let [[_ ^String parent-id] (re-matches #".*/(\d+)/$" (:location collection))]
    (t2/select-one-fn :name :model/Collection :id (Integer. parent-id))))

(defmethod assert-loaded-entity :model/Collection
  [collection _]
  (case (:name collection)
    "My Nested Collection"              (is (= "My Collection" (collection-parent-name collection)))
    "My Collection"                     (is (= "/" (:location collection)))
    "Snippet Collection"                (is (= "/" (:location collection)))
    "Nested Snippet Collection"         (is (= "Snippet Collection" (collection-parent-name collection)))
    "Crowberto's Personal Collection"   (is (= "/" (:location collection)))
    "Nested Personal Collection"        (is (= "Crowberto Corv's Personal Collection"
                                               (collection-parent-name collection)))
    "Deeply Nested Personal Collection" (is (= "Nested Personal Collection"
                                               (collection-parent-name collection)))
    "Felicia's Personal Collection"     (is (nil? (:name collection))
                                            "Should not have loaded different user's PC")
    "Felicia's Nested Collection"       (is (nil? (:name collection))
                                            "Should not have loaded different user's PC"))
  collection)

(defmethod assert-loaded-entity :model/NativeQuerySnippet
  [snippet {:keys [entities]}]
  (when-let [orig-snippet (first (filter (every-pred #(mi/instance-of? :model/NativeQuerySnippet %)
                                                     #(= (:name snippet) (:name %))) (map last entities)))]
    (is (some? orig-snippet))
    (is (= (select-keys orig-snippet [:name :description :content])
           (select-keys snippet [:name :description :content])))
    snippet))

(defn- id->name [model id]
  (t2/select-one-fn :name model :id id))

(defn- assert-price-click [click target-id]
  ;; first, it should be pointing to "My Card"
  (is (= "My Card" (id->name :model/Card target-id)))
  (let [param-mapping (:parameterMapping click)]
    ;; also, the parameter mappings should have been preserved
    (is (not-empty param-mapping))
    (let [mapping-key     (-> param-mapping
                              keys
                              first)
          mapping-keyname (#'mb.viz/keyname mapping-key)
          [_ f1-id f2-id] (re-matches #".*\[\"field(?:-id)?\",(\d+),.*\[\"field(?:-id)?\",(\d+),.*" mapping-keyname)
          f1              (t2/select-one :model/Field :id (Integer/parseInt f1-id))
          f2              (t2/select-one :model/Field :id (Integer/parseInt f2-id))
          dimension       (get-in param-mapping [mapping-key :target :dimension])]
      ;; the source and target fields should be category_id and price, respectively
      ;; for an explanation of why both cases are allowed, see `case field-nm` below
      (is (contains? #{"category_id" "CATEGORY_ID"} (:name f1)))
      (is (contains? #{"price" "PRICE"} (:name f2)))
      (is (= {:dimension [:field (u/the-id f2) {:source-field (u/the-id f1)}]} dimension)))))

(defmethod assert-loaded-entity :model/Dashboard
  [dashboard _]
  (testing "The dashboard card series were loaded correctly"
    (when (= "My Dashboard" (:name dashboard))
      (doseq [dashcard (t2/select :model/DashboardCard :dashboard_id (u/the-id dashboard))]
        (doseq [series (t2/select :model/DashboardCardSeries :dashboardcard_id (u/the-id dashcard))]
          ;; check that the linked :card_id matches the expected name for each in the series
          ;; based on the entities declared in test_util.clj
          (let [series-pos    (:position series)
                expected-name (case (int series-pos)
                                0 "My Card"
                                1 "My Nested Card"
                                2 ts/root-card-name)]
            (is (= expected-name (t2/select-one-fn :name :model/Card :id (:card_id series))))
            (case (int series-pos)
              1
              (testing "Top level click action was preserved for dashboard card"
                (let [viz-settings (:visualization_settings dashcard)
                      cb           (:click_behavior viz-settings)]
                  (is (not-empty cb))
                  (is (int? (:targetId cb)))
                  (is (= "My Nested Query Card" (t2/select-one-fn :name :model/Card :id (:targetId cb))))))
              2
              (testing "Column level click actions were preserved for dashboard card"
                (let [viz-settings   (:visualization_settings dashcard)
                      check-click-fn (fn [[col-key col-value]]
                                       (let [col-ref   (mb.viz/parse-db-column-ref col-key)
                                             {:keys [::mb.viz/field-id ::mb.viz/column-name]} col-ref
                                             click-bhv (get col-value :click_behavior)
                                             target-id (get click-bhv :targetId)]
                                         (cond
                                           field-id (let [field-nm (id->name :model/Field field-id)]
                                                      (case field-nm
                                                        ;; consider either case for these fields, since they are all
                                                        ;; caps in some (ex: H2) and lowercase in others (ex: Postgres)
                                                        ;; this is simpler than the alternative (dynamically loading
                                                        ;; the expected name based on driver)
                                                        ("price" "PRICE") (assert-price-click click-bhv target-id)
                                                        ("name" "NAME") (is (=
                                                                             "Root Dashboard"
                                                                             (id->name :model/Dashboard target-id)))
                                                        ("latitude" "LATITUDE") (is (= {:parameterMapping {}
                                                                                        :type             "crossfilter"}
                                                                                       click-bhv))))
                                           column-name
                                           (case column-name
                                             "Price Known" (is (= {:type         "link"
                                                                   :linkType     "url"
                                                                   :linkTemplate "/price-info"} click-bhv))))))]
                  (is (not-empty viz-settings))
                  (doall (map check-click-fn (:column_settings viz-settings)))))
              true))) ; nothing to assert for other series numberst
        (when-let [virt-card (get-in dashcard [:visualization_settings :virtual_card])]
          (is (= ts/virtual-card virt-card))
          (is (= "Textbox Card" (get-in dashcard [:visualization_settings :text])))))))
  dashboard)

(defmethod assert-loaded-entity :model/Pulse
  [pulse _]
  (is (some? pulse))
  (let [pulse-cards (t2/select :model/PulseCard :pulse_id (u/the-id pulse))]
    (is (= 2 (count pulse-cards)))
    (is (= #{ts/root-card-name "My Card"}
           (into #{} (map (partial t2/select-one-fn :name :model/Card :id) (map :card_id pulse-cards)))))))

(defmethod assert-loaded-entity :default
  [entity _]
  entity)

;; If this test fails after adding a new column, add the column to the list of columns in `metabase-enterprise.serialization.serialize/strip-crud`
(deftest dump-load-entities-test
  (try
    ;; in case it already exists
    (u/ignore-exceptions
      (delete-directory! dump-dir))
    ;; TODO: Examine whether the test could work without :metadata/key-constraints.
    (mt/test-drivers (-> (mt/normal-drivers-with-feature :basic-aggregations :binning :expressions
                                                         :metadata/key-constraints)
                         ;; We will run this roundtrip test against any database supporting these features ^ except
                         ;; certain ones for specific reasons, outlined below.
                         ;;
                         ;; Being able to support all of these would require some significant amount of effort (either
                         ;; to come up with "generic" native queries that will work on all of them, or else possibly
                         ;; using Metabase itself to spit out the native queries, but even then we would need to splice
                         ;; different strings together (ex: the SELECT clause, but replace the WHERE clause with the
                         ;; subquery from the linked card parameter).
                         ;;
                         ;; Because this feature is just about testing serialization itself, and not yet another test of
                         ;; query processor, this seems like an acceptable tradeoff (ex: if they dump and load to the
                         ;; same native form on one database, then it's likely they would on any, since that is
                         ;; orthogonal to the issues that serialization has when performing this roundtrip).
                         (disj :oracle    ; no bare table names allowed
                               :databricks ; table name requires schema prefix with current implementation
                               :redshift  ; bare table name doesn't work; it's test_data_venues instead of venues
                               :snowflake ; bare table name doesn't work; it's test_data_venues instead of venues
                               :sqlserver ; ORDER BY not allowed not allowed in derived tables (subselects)
                               :vertica))   ; bare table name doesn't work; it's test_data_venues instead of venues

      (mt/with-premium-features #{:serialization}
        (let [fingerprint (ts/with-world
                            (v1-dump! dump-dir {:user        (:email (test.users/fetch-user :crowberto))
                                                :only-db-ids #{db-id}})
                            {:query-results (gather-orig-results [card-id
                                                                  card-arch-id
                                                                  card-id-root
                                                                  card-id-nested
                                                                  card-id-nested-query
                                                                  card-id-native-query
                                                                  card-id-root-to-collection
                                                                  card-id-collection-to-root
                                                                  card-id-template-tags
                                                                  card-id-filter-agg
                                                                  card-id-temporal-unit
                                                                  card-id-with-native-snippet
                                                                  card-id-temporal-unit
                                                                  card-join-card-id
                                                                  card-id-pivot-table])
                             :collections   (gather-collections [card-id
                                                                 card-arch-id
                                                                 card-id-root
                                                                 card-id-nested
                                                                 card-id-nested-query
                                                                 card-id-native-query
                                                                 card-id-root-to-collection
                                                                 card-id-collection-to-root
                                                                 card-id-template-tags
                                                                 card-id-filter-agg
                                                                 card-id-temporal-unit
                                                                 card-id-with-native-snippet
                                                                 card-id-temporal-unit
                                                                 card-join-card-id
                                                                 card-id-pivot-table])
                             :entities      [[:model/Database           (t2/select-one :model/Database :id db-id)]
                                             [:model/Table              (t2/select-one :model/Table :id table-id)]
                                             [:model/Table              (t2/select-one :model/Table :id table-id-categories)]
                                             [:model/Table              (t2/select-one :model/Table :id table-id-users)]
                                             [:model/Table              (t2/select-one :model/Table :id table-id-checkins)]
                                             [:model/Field              (t2/select-one :model/Field :id numeric-field-id)]
                                             [:model/Field              (t2/select-one :model/Field :id name-field-id)]
                                             [:model/Field              (t2/select-one :model/Field :id category-field-id)]
                                             [:model/Field              (t2/select-one :model/Field :id latitude-field-id)]
                                             [:model/Field              (t2/select-one :model/Field :id longitude-field-id)]
                                             [:model/Field              (t2/select-one :model/Field :id category-pk-field-id)]
                                             [:model/Field              (t2/select-one :model/Field :id date-field-id)]
                                             [:model/Field              (t2/select-one :model/Field :id user-id-field-id)]
                                             [:model/Field              (t2/select-one :model/Field :id users-pk-field-id)]
                                             [:model/Field              (t2/select-one :model/Field :id last-login-field-id)]
                                             [:model/Collection         (t2/select-one :model/Collection :id collection-id)]
                                             [:model/Collection         (t2/select-one :model/Collection :id collection-id-nested)]
                                             [:model/Collection         (t2/select-one :model/Collection :id personal-collection-id)]
                                             [:model/Collection         (t2/select-one :model/Collection :id pc-nested-id)]
                                             [:model/Collection         (t2/select-one :model/Collection :id pc-deeply-nested-id)]
                                             [:model/Segment            (t2/select-one :model/Segment :id segment-id)]
                                             [:model/Dashboard          (t2/select-one :model/Dashboard :id dashboard-id)]
                                             [:model/Dashboard          (t2/select-one :model/Dashboard :id root-dashboard-id)]
                                             [:model/Card               (t2/select-one :model/Card :id card-id)]
                                             [:model/Card               (t2/select-one :model/Card :id card-arch-id)]
                                             [:model/Card               (t2/select-one :model/Card :id card-id-root)]
                                             [:model/Card               (t2/select-one :model/Card :id card-id-nested)]
                                             [:model/Card               (t2/select-one :model/Card :id card-id-nested-query)]
                                             [:model/Card               (t2/select-one :model/Card :id card-id-native-query)]
                                             [:model/DashboardCard      (t2/select-one :model/DashboardCard :id dashcard-id)]
                                             [:model/DashboardCard      (t2/select-one :model/DashboardCard :id dashcard-top-level-click-id)]
                                             [:model/DashboardCard      (t2/select-one :model/DashboardCard :id dashcard-with-click-actions)]
                                             [:model/Card               (t2/select-one :model/Card :id card-id-root-to-collection)]
                                             [:model/Card               (t2/select-one :model/Card :id card-id-collection-to-root)]
                                             [:model/Card               (t2/select-one :model/Card :id card-id-template-tags)]
                                             [:model/Card               (t2/select-one :model/Card :id card-id-filter-agg)]
                                             [:model/Card               (t2/select-one :model/Card :id card-id-temporal-unit)]
                                             [:model/Pulse              (t2/select-one :model/Pulse :id pulse-id)]
                                             [:model/DashboardCard      (t2/select-one :model/DashboardCard :id dashcard-with-textbox-id)]
                                             [:model/NativeQuerySnippet (t2/select-one :model/NativeQuerySnippet :id snippet-id)]
                                             [:model/Collection         (t2/select-one :model/Collection :id snippet-collection-id)]
                                             [:model/Collection         (t2/select-one :model/Collection :id snippet-nested-collection-id)]
                                             [:model/NativeQuerySnippet (t2/select-one :model/NativeQuerySnippet :id nested-snippet-id)]
                                             [:model/Card               (t2/select-one :model/Card :id card-id-with-native-snippet)]
                                             [:model/Card               (t2/select-one :model/Card :id card-join-card-id)]
                                             [:model/Card               (t2/select-one :model/Card :id card-id-pivot-table)]]})]
          (with-world-cleanup
            (v1-load! dump-dir {:on-error :continue :mode :skip})
            (mt/with-db (t2/select-one :model/Database :name ts/temp-db-name)
              (doseq [[model entity] (:entities fingerprint)]
                (testing (format "%s \"%s\"" (type model) (:name entity))
                  (is (or (-> entity :name nil?)
                          (when-let [loaded (t2/select-one model :name (:name entity))]
                            (assert-loaded-entity loaded fingerprint))
                          (and (-> entity :archived) ; archived card hasn't been dump-loaded
                               (= (:name entity) "My Arch Card"))
                                         ;; Rasta's Personal Collection was not loaded
                          (= "Felicia's Personal Collection" (:name entity)))
                      (str " failed " (pr-str entity)))))
              fingerprint)))))
    (finally
      (delete-directory! dump-dir))))

(deftest resolve-dashboard-parameters-test
  (let [parameters [{:values_source_config {:card_id "foo"}}]]
    (with-redefs [load/fully-qualified-name->card-id {"foo" 1}]
      (is (= [1] (mapv (comp :card_id :values_source_config) (#'load/resolve-dashboard-parameters parameters)))))))
