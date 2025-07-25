(ns metabase-enterprise.sandbox.models.group-table-access-policy
  "Model definition for sandboxes, aka Group Table Access Policies (old name). A sandbox is used to control access to a
  certain Table for a certain PermissionsGroup. Whenever a member of that group attempts to query the Table in question,
  a Saved Question specified by the GTAP is instead used as the source of the query.

  See documentation in [[metabase.permissions.models.permissions]] for more information about the Metabase permissions system."
  (:require
   [medley.core :as m]
   [metabase.audit-app.core :as audit]
   [metabase.classloader.core :as classloader]
   [metabase.legacy-mbql.normalize :as mbql.normalize]
   [metabase.models.interface :as mi]
   [metabase.permissions.core :as perms]
   [metabase.premium-features.core :refer [defenterprise]]
   [metabase.query-processor.error-type :as qp.error-type]
   [metabase.request.core :as request]
   [metabase.util :as u]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   [metabase.warehouses.models.database :as database]
   [methodical.core :as methodical]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(methodical/defmethod t2/table-name :model/GroupTableAccessPolicy [_model] :sandboxes)

(doto :model/GroupTableAccessPolicy
  (derive :metabase/model)
  ;;; only admins can work with sandboxes
  (derive ::mi/read-policy.superuser)
  (derive ::mi/write-policy.superuser))

(defn- normalize-attribute-remapping-targets [attribute-remappings]
  (m/map-vals
   mbql.normalize/normalize
   attribute-remappings))

(t2/deftransforms :model/GroupTableAccessPolicy
  {:attribute_remappings {:in  (comp mi/json-in normalize-attribute-remapping-targets)
                          :out (comp normalize-attribute-remapping-targets mi/json-out-without-keywordization)}})

(defn table-field-names->cols
  "Return a mapping of field names to corresponding cols for given table."
  [table-id]
  (into {} (for [col (request/as-admin
                       ((requiring-resolve 'metabase.query-processor.preprocess/query->expected-cols)
                        {:database (database/table-id->database-id table-id)
                         :type     :query
                         :query    {:source-table table-id}}))]
             [(:name col) col])))

(defn check-column-types-match
  "Assert that the base type of `col`, returned by a GTAP source query, matches the base type of `table-col`, a column
  from the original Table being sandboxed."
  {:arglists '([col table-col])}
  [col {table-col-base-type :base_type}]
  ;; These errors might get triggered by API endpoints or by the QP (this code is used in the
  ;; `row-level-restrictions` middleware). So include `:type` and `:status-code` information in the ExceptionInfo
  ;; data so it can be passed along if applicable.
  (when table-col-base-type
    (when-not (isa? (keyword (:base_type col)) table-col-base-type)
      (let [msg (tru "Sandbox Questions can''t return columns that have different types than the Table they are sandboxing.")]
        (throw (ex-info msg
                        {:type        qp.error-type/bad-configuration
                         :status-code 400
                         :message     msg
                         :new-col     col
                         :expected    table-col-base-type
                         :actual      (:base_type col)}))))))

(defn- merge-sandbox-into-graph
  "Merges a single sandboxing policy into the permissions graph. Adjusts permissions at the database or schema level,
  ensuring table-level permissions are set appropriately."
  [graph group-id table-id db-id schema perm-location sandbox-value]
  (let [db-path (concat [group-id db-id] perm-location)
        db-perm (get-in graph db-path)
        schema-perm (get db-perm schema)
        default-table-perm (if (keyword? db-perm)
                             db-perm
                             (when (keyword schema-perm) schema-perm))
        ;; If perms were set at the database or schema-level before, we might need to add granular values for all tables
        ;; in the database or schema, so they show correctly in the UI.
        tables (when (or (keyword? db-perm) (keyword? schema-perm))
                 (t2/select [:model/Table :id :db_id :schema]
                            {:where [:and
                                     [:= :db_id db-id]
                                     (when (keyword? schema-perm)
                                       [:= :schema schema])]}))
        ;; Remove the overarching database or schema permission so that we can add the granular table-level permissions
        graph (cond
                (and tables (keyword? db-perm))
                (m/dissoc-in graph db-path)

                (and tables (keyword? schema-perm))
                (m/dissoc-in graph (concat db-path [(or schema "")]))

                :else
                graph)
        ;; Apply granular permissions to each table
        granular-graph (if tables
                         (reduce (fn [g {:keys [id schema]}]
                                   (assoc-in g (concat db-path [(or schema "") id]) default-table-perm))
                                 graph
                                 tables)
                         graph)]
    ;; Set `:segmented` (aka sandboxed) permissions for the target table
    (assoc-in granular-graph
              (concat db-path [(or schema "") table-id])
              sandbox-value)))

(defenterprise add-sandboxes-to-permissions-graph
  "Augments a provided permissions graph with active sandboxing policies."
  :feature :sandboxes
  [graph & {:keys [group-ids group-id db-id audit?]}]
  (let [sandboxes (t2/select :model/GroupTableAccessPolicy
                             {:select [:s.group_id :s.table_id :t.db_id :t.schema]
                              :from [[:sandboxes :s]]
                              :join [[:metabase_table :t] [:= :s.table_id :t.id]]
                              :where [:and
                                      (when group-id [:= :s.group_id group-id])
                                      (when group-ids [:in :s.group_id group-ids])
                                      (when db-id [:= :t.db_id db-id])
                                      (when-not audit? [:not [:= :t.db_id audit/audit-db-id]])]})]
    ;; Incorporate each sandbox policy into the permissions graph.
    (reduce (fn [acc {:keys [group_id table_id db_id schema]}]
              (merge-sandbox-into-graph acc group_id table_id db_id schema [:view-data] :sandboxed))
            graph
            sandboxes)))

(mu/defn check-columns-match-table
  "Make sure the result metadata data columns for the Card associated with a sandbox match up with the columns in the Table
  that's getting sandboxed The base types of the Card columns can derive from the respective base types of the columns in
  the Table itself, but you cannot return an entirely different type. Extra columns in the sandboxing Card are ignored."
  ([{card-id :card_id, table-id :table_id}]
   ;; not all sandboxes have Cards
   (when card-id
     ;; not all Cards have saved result metadata
     (when-let [result-metadata (t2/select-one-fn :result_metadata :model/Card :id card-id)]
       (check-columns-match-table table-id result-metadata))))

  ([table-id :- ms/PositiveInt result-metadata-columns]
   ;; prevent circular refs
   (classloader/require 'metabase.query-processor)
   (let [table-cols (table-field-names->cols table-id)]
     (doseq [col  result-metadata-columns
             :let [table-col (get table-cols (:name col))]]
       (check-column-types-match col table-col)))))

(defenterprise pre-update-check-sandbox-constraints
  "If a Card is updated, and its result metadata changes, check that these changes do not violate the constraints placed
  on sandboxes (the Card cannot add fields or change types vs. the original Table)."
  :feature :sandboxes
  [{new-result-metadata :result_metadata, card-id :id} changes]
  (when (contains? changes :result_metadata)
    (when-let [gtaps-using-this-card (not-empty (t2/select [:model/GroupTableAccessPolicy :id :table_id] :card_id card-id))]
      (let [original-result-metadata (t2/select-one-fn :result_metadata :model/Card :id card-id)]
        (when-not (= original-result-metadata new-result-metadata)
          (doseq [{table-id :table_id} gtaps-using-this-card]
            (try
              (check-columns-match-table table-id new-result-metadata)
              (catch clojure.lang.ExceptionInfo e
                (throw (ex-info (str (tru "Cannot update Card: Card is used for Sandboxing, and updates would violate sandbox rules.")
                                     " "
                                     (.getMessage e))
                                (ex-data e)
                                e))))))))))

(defenterprise upsert-sandboxes!
  "Create new `sandboxes` or update existing ones. If a sandbox has an `:id` it will be updated, otherwise it will be
  created. New sandboxes must have a `:table_id` corresponding to a sandboxed query path in the `permissions` table;
  if this does not exist, the sandbox will not be created."
  :feature :sandboxes
  [sandboxes]
  (doall
   (for [sandbox sandboxes]
     (if-let [id (:id sandbox)]
       ;; Only update `card_id` and/or `attribute_remappings` if the values are present in the body of the request.
       ;; This allows existing values to be "cleared" by being set to nil
       (do
         (when (some #(contains? sandbox %) [:card_id :attribute_remappings])
           (t2/update! :model/GroupTableAccessPolicy
                       id
                       (u/select-keys-when sandbox :present #{:card_id :attribute_remappings})))
         (t2/select-one :model/GroupTableAccessPolicy :id id))
       (first (t2/insert-returning-instances! :model/GroupTableAccessPolicy sandbox))))))

(t2/define-before-insert :model/GroupTableAccessPolicy
  [{:keys [table_id group_id], :as gtap}]
  (let [db-id (database/table-id->database-id table_id)]
    ;; Remove native query access to the DB when saving a sandbox
    (when (= (perms/table-permission-for-groups #{group_id} :perms/create-queries db-id table_id) :query-builder-and-native)
      (perms/set-database-permission! group_id db-id :perms/create-queries :query-builder)))
  (u/prog1 gtap
    (check-columns-match-table gtap)))

(t2/define-before-update :model/GroupTableAccessPolicy
  [{:keys [id], :as updates}]
  (u/prog1 updates
    (let [original (t2/original updates)
          updated  (merge original updates)]
      (when-not (= (:table_id original) (:table_id updated))
        (throw (ex-info (tru "You cannot change the table ID of a sandbox once it has been created.")
                        {:id          id
                         :status-code 400})))
      (when (:card_id updates)
        (check-columns-match-table updated)))))
