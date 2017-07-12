(ns metabase.models.field-values
  (:require [clojure.tools.logging :as log]
            [metabase.sfc.classify.infer-special-type :as infer-special-type]
            [metabase.util :as u]
            [toucan
             [db :as db]
             [models :as models]]))

;; ## Entity + DB Multimethods

(models/defmodel FieldValues :metabase_fieldvalues)

(u/strict-extend (class FieldValues)
  models/IModel
  (merge models/IModelDefaults
         {:properties  (constantly {:timestamped? true})
          :types       (constantly {:human_readable_values :json, :values :json})
          :post-select (u/rpartial update :human_readable_values #(or % {}))}))

;; columns:
;; *  :id
;; *  :field_id
;; *  :updated_at             WHY! I *DESPISE* THESE USELESS FIELDS
;; *  :created_at
;; *  :values                 (JSON-encoded array like ["table" "scalar" "pie"])
;; *  :human_readable_values  (JSON-encoded map like {:table "Table" :scalar "Scalar"}

;; ## `FieldValues` Helper Functions

(defn field-should-have-field-values?
  "Should this `Field` be backed by a corresponding `FieldValues` object?"
  {:arglists '([field])}
  [{:keys [base_type special_type visibility_type name] :as field}]
  {:pre [#_visibility_type
         (contains? field :base_type)
         ;; this used to happen both before and after field_values where cached.
         #_(contains? field :special_type)]} ;; requirement set aside in refactor, special type now crated after field values are saved.
  (let [infered-special-type (or special_type (infer-special-type/infer-field-special-type name base_type))]
    (and (not (contains? #{:retired :sensitive :hidden :details-only} (keyword visibility_type)))
         (not (isa? (keyword base_type) :type/DateTime))
         (not (isa? base_type :type/Collection))
         (not (= base_type :type/*))
         (or (isa? (keyword base_type) :type/Boolean)
             (isa? (keyword infered-special-type) :type/Category)
             (nil? infered-special-type))))) ;; This is a case that used to be handled by an implicit coersion to :type/Category

(defn- create-field-values!
  "Create `FieldValues` for a `Field`."
  {:arglists '([field] [field human-readable-values])}
  [{field-id :id, field-name :name, :as field} & [human-readable-values]]
  {:pre [(integer? field-id)]}
  (log/debug (format "Creating FieldValues for Field %s..." (or field-name field-id))) ; use field name if available
  (db/insert! FieldValues
    :field_id              field-id
    :values                ((resolve 'metabase.db.metadata-queries/field-distinct-values) field)
    :human_readable_values human-readable-values))

(defn update-field-values!
  "Update the `FieldValues` for FIELD, creating them if needed"
  [{field-id :id, :as field}]
  {:pre [(integer? field-id)
         (field-should-have-field-values? field)]}
  (if-let [field-values (FieldValues :field_id field-id)]
    (db/update! FieldValues (u/get-id field-values)
      :values ((resolve 'metabase.db.metadata-queries/field-distinct-values) field))
    (create-field-values! field)))

(defn create-field-values-if-needed!
  "Create `FieldValues` for a `Field` if they *should* exist but don't already exist.
   Returns the existing or newly created `FieldValues` for `Field`."
  {:arglists '([field] [field human-readable-values])}
  [{field-id :id :as field} & [human-readable-values]]
  {:pre [(integer? field-id)]}
  (when (field-should-have-field-values? field)
    (or (FieldValues :field_id field-id)
        (create-field-values! field human-readable-values))))

(defn save-field-values!
  "Save the `FieldValues` for FIELD-ID, creating them if needed, otherwise updating them."
  [field-id values]
  {:pre [(integer? field-id) (coll? values)]}
  (if-let [field-values (FieldValues :field_id field-id)]
    (db/update! FieldValues (u/get-id field-values), :values values)
    (db/insert! FieldValues :field_id field-id, :values values)))

(defn clear-field-values!
  "Remove the `FieldValues` for FIELD-ID."
  [field-id]
  {:pre [(integer? field-id)]}
  (db/delete! FieldValues :field_id field-id))
