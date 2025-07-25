(ns metabase.sync.analyze.fingerprint
  "Analysis sub-step that takes a sample of values for a Field and saving a non-identifying fingerprint
   used for classification. This fingerprint is saved as a column on the Field it belongs to."
  (:require
   [clojure.set :as set]
   [honey.sql.helpers :as sql.helpers]
   [metabase.analyze.core :as analyze]
   [metabase.app-db.core :as app-db]
   [metabase.driver :as driver]
   [metabase.driver.util :as driver.u]
   [metabase.lib.schema.metadata.fingerprint :as lib.schema.metadata.fingerprint]
   [metabase.query-processor.store :as qp.store]
   [metabase.sync.interface :as i]
   [metabase.sync.util :as sync-util]
   [metabase.util :as u]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.registry :as mr]
   [metabase.util.malli.schema :as ms]
   [metabase.warehouse-schema.models.field :as field]
   [metabase.warehouse-schema.models.table :as table]
   [redux.core :as redux]
   [toucan2.core :as t2]))

(defn incomplete-analysis-kvs
  "Key-value pairs corresponding to the state of Fields that have the latest fingerprint, but have not yet
   *completed* analysis. All Fields who get new fingerprints should get marked as having the latest fingerprint
   version, but we'll clear their values for `last_analyzed`. This way we know these fields haven't 'completed'
   analysis for the latest fingerprints. This is a function because `*latest-fingerprint-version* may be rebound
   in tests."
  []
  {:fingerprint_version i/*latest-fingerprint-version*
   :last_analyzed       nil})

(mu/defn- save-fingerprint!
  [field       :- i/FieldInstance
   fingerprint :- [:maybe ::lib.schema.metadata.fingerprint/fingerprint]]
  (log/debugf "Saving fingerprint for %s" (sync-util/name-for-logging field))
  (t2/update! :model/Field (u/the-id field) (merge (incomplete-analysis-kvs) {:fingerprint fingerprint})))

(mr/def ::FingerprintStats
  [:map
   [:no-data-fingerprints   ms/IntGreaterThanOrEqualToZero]
   [:failed-fingerprints    ms/IntGreaterThanOrEqualToZero]
   [:updated-fingerprints   ms/IntGreaterThanOrEqualToZero]
   [:fingerprints-attempted ms/IntGreaterThanOrEqualToZero]])

(mu/defn empty-stats-map :- ::FingerprintStats
  "The default stats before any fingerprints happen"
  [fields-count :- ms/IntGreaterThanOrEqualToZero]
  {:no-data-fingerprints   0
   :failed-fingerprints    0
   :updated-fingerprints   0
   :fingerprints-attempted fields-count})

(def ^:private ^:dynamic *truncation-size*
  "The maximum size of :type/Text to be selected from the database in `table-rows-sample`. In practice we see large
  text blobs and want to balance taking enough for distinct counts and but not so much that we risk out of memory
  issues when syncing."
  1234)

(mu/defn- fingerprint-fields!
  [table  :- i/TableInstance
   fields :- [:maybe [:sequential i/FieldInstance]]]
  (let [rff (fn [_metadata]
              (redux/post-complete
               (analyze/fingerprint-fields fields)
               (fn [fingerprints]
                 (reduce (fn [count-info [field fingerprint]]
                           (cond
                             (instance? Throwable fingerprint)
                             (update count-info :failed-fingerprints inc)

                             (some-> fingerprint :global :distinct-count zero?)
                             (update count-info :no-data-fingerprints inc)

                             :else
                             (do
                               (save-fingerprint! field fingerprint)
                               (update count-info :updated-fingerprints inc))))
                         (empty-stats-map (count fingerprints))
                         (map vector fields fingerprints)))))
        driver (driver.u/database->driver (table/database table))
        opts {:truncation-size *truncation-size*}]
    (driver/table-rows-sample driver table fields rff opts)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                    WHICH FIELDS NEED UPDATED FINGERPRINTS?                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Logic for building the somewhat-complicated query we use to determine which Fields need new Fingerprints
;;
;; This ends up giving us a SQL query that looks something like:
;;
;; SELECT *
;; FROM metabase_field
;; WHERE active = true
;;   AND (semantic_type NOT IN ('type/PK') OR semantic_type IS NULL)
;;   AND preview_display = true
;;   AND visibility_type <> 'retired'
;;   AND table_id = 1
;;   AND ((fingerprint_version < 1 AND
;;         base_type IN ("type/Longitude", "type/Latitude", "type/Integer"))
;;        OR
;;        (fingerprint_version < 2 AND
;;         base_type IN ("type/Text", "type/SerializedJSON")))

(mu/defn- base-types->descendants :- [:maybe [:set ms/FieldTypeKeywordOrString]]
  "Given a set of `base-types` return an expanded set that includes those base types as well as all of their
  descendants. These types are converted to strings so HoneySQL doesn't confuse them for columns."
  [base-types :- [:set ms/FieldType]]
  (into #{}
        (comp (mapcat (fn [base-type]
                        (cons base-type (descendants base-type))))
              (map u/qualified-name))
        base-types))

;; It's even cooler if we could generate efficient SQL that looks at what types have already
;; been marked for upgrade so we don't need to generate overly-complicated queries.
;;
;; e.g. instead of doing:
;;
;; WHERE ((version < 2 AND base_type IN ("type/Integer", "type/BigInteger", "type/Text")) OR
;;        (version < 1 AND base_type IN ("type/Boolean", "type/Integer", "type/BigInteger")))
;;
;; we could do:
;;
;; WHERE ((version < 2 AND base_type IN ("type/Integer", "type/BigInteger", "type/Text")) OR
;;        (version < 1 AND base_type IN ("type/Boolean")))
;;
;; (In the example above, something that is a `type/Integer` or `type/Text` would get upgraded
;; as long as it's less than version 2; so no need to also check if those types are less than 1, which
;; would always be the case.)
;;
;; This way we can also completely omit adding clauses for versions that have been "eclipsed" by others.
;; This would keep the SQL query from growing boundlessly as new fingerprint versions are added
(mu/defn- versions-clauses :- [:maybe [:sequential :any]]
  []
  ;; keep track of all the base types (including descendants) for each version, starting from most recent
  (let [versions+base-types (reverse (sort-by first (seq i/*fingerprint-version->types-that-should-be-re-fingerprinted*)))
        already-seen        (atom #{})]
    (for [[version base-types] versions+base-types
          :let  [descendants  (base-types->descendants base-types)
                 not-yet-seen (set/difference descendants @already-seen)]
          ;; if all the descendants of any given version have already been seen, we can skip this clause altogether
          :when (seq not-yet-seen)]
      ;; otherwise record the newly seen types and generate an appropriate clause
      (do
        (swap! already-seen set/union not-yet-seen)
        [:and
         [:< :fingerprint_version version]
         [:in :base_type not-yet-seen]]))))

(def ^:private fields-to-fingerprint-base-clause
  "Base clause to get fields for fingerprinting. When refingerprinting, run as is. When fingerprinting in analysis, only
  look for fields without a fingerprint or whose version can be updated. This clauses is added on
  by [[versions-clauses]]."
  [:and
   [:= :active true]
   [:or
    [:not (app-db/isa :semantic_type :type/PK)]
    [:= :semantic_type nil]]
   [:not-in :visibility_type ["retired" "sensitive"]]
   [:not-in :base_type (conj (app-db/type-keyword->descendants :type/fingerprint-unsupported)
                             (u/qualified-name :type/*))]])

(def ^:dynamic *refingerprint?*
  "Whether we are refingerprinting or doing the normal fingerprinting. Refingerprinting should get fields that already
  are analyzed and have fingerprints."
  false)

(mu/defn- honeysql-for-fields-that-need-fingerprint-updating :- [:map
                                                                 [:where :any]]
  "Return appropriate WHERE clause for all the Fields whose Fingerprint needs to be re-calculated."
  ([]
   {:where (cond-> fields-to-fingerprint-base-clause
             (not *refingerprint?*) (conj (cons :or (versions-clauses))))})

  ([table :- i/TableInstance]
   (sql.helpers/where (honeysql-for-fields-that-need-fingerprint-updating)
                      [:= :table_id (u/the-id table)])))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                      FINGERPRINTING ALL FIELDS IN A TABLE                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

(mu/defn- fields-to-fingerprint :- [:maybe [:sequential i/FieldInstance]]
  "Return a sequences of Fields belonging to `table` for which we should generate (and save) fingerprints.
   This should include NEW fields that are active and visible."
  [table :- i/TableInstance]
  (seq (t2/select :model/Field
                  (honeysql-for-fields-that-need-fingerprint-updating table))))

(mu/defn fingerprint-table!
  "Generate and save fingerprints for all the Fields in `table` that have not been previously analyzed."
  [table :- i/TableInstance]
  (if-let [fields (fields-to-fingerprint table)]
    (do
      (log/infof "Fingerprinting %s fields in table %s" (count fields) (sync-util/name-for-logging table))
      (let [stats
            (sync-util/with-returning-throwable (format "Error fingerprinting %s" (sync-util/name-for-logging table))
              (fingerprint-fields! table fields))]
        (if (:throwable stats)
          (merge (empty-stats-map 0) stats)
          stats)))
    (empty-stats-map 0)))

(def ^:private LogProgressFn
  [:=> [:cat :string [:schema i/TableInstance]] :any])

(mu/defn- fingerprint-fields-for-db!*
  "Invokes `fingerprint-table!` on every table in `database`"
  ([database        :- i/DatabaseInstance
    log-progress-fn :- LogProgressFn]
   (fingerprint-fields-for-db!* database log-progress-fn (constantly true)))

  ([database        :- i/DatabaseInstance
    log-progress-fn :- LogProgressFn
    continue?       :- [:=> [:cat ::FingerprintStats] :any]]
   (qp.store/with-metadata-provider (u/the-id database)
     (let [tables (if *refingerprint?*
                    (sync-util/refingerprint-reducible-sync-tables database)
                    (sync-util/reducible-sync-tables database))]
       (reduce (fn [acc table]
                 (log-progress-fn (if *refingerprint?* "refingerprint-fields" "fingerprint-fields") table)
                 (let [ret (fingerprint-table! table)
                       new-acc (let [ret (if (:throwable ret)
                                           (-> ret
                                               (dissoc :throwable)
                                               (update :failed-fingerprints inc))
                                           ret)]
                                 (merge-with + acc ret))]
                   (if (and (continue? new-acc) (not (sync-util/abandon-sync? ret)))
                     new-acc
                     (reduced new-acc))))
               (empty-stats-map 0)
               tables)))))

(mu/defn fingerprint-fields-for-db!
  "Invokes [[fingerprint-table!]] on every table in `database`"
  [database        :- i/DatabaseInstance
   log-progress-fn :- LogProgressFn]
  (if (driver.u/supports? (:engine database) :fingerprint database)
    (fingerprint-fields-for-db!* database log-progress-fn)
    (empty-stats-map 0)))

(def ^:private max-refingerprint-field-count
  "Maximum number of fields to refingerprint. Balance updating our fingerprinting values while not spending too much
  time in the db."
  1000)

(mu/defn refingerprint-fields-for-db!
  "Invokes [[fingeprint-fields!]] on every table in `database` up to some limit."
  [database        :- i/DatabaseInstance
   log-progress-fn :- LogProgressFn]
  (binding [*refingerprint?* true]
    (fingerprint-fields-for-db!* database
                                 log-progress-fn
                                 (fn [stats-acc]
                                   (< (:fingerprints-attempted stats-acc) max-refingerprint-field-count)))))

(mu/defn refingerprint-field
  "Refingerprint a field"
  [field :- i/FieldInstance]
  (let [table (field/table field)]
    (fingerprint-fields! table [field])))
