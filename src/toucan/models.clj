(ns toucan.models
  (:require [toucan
             [dispatch :as dispatch]
             [instance :as instance]]
            [toucan.models.options :as options])
  (:import clojure.lang.MultiFn))

;; NOCOMMIT
(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))

;;;                                                      defmodel
;;; ==================================================================================================================

(defmacro defmodel
  "Define a new \"model\". Models encapsulate information and behaviors related to a specific table in the application
  DB."
  ;; TODO - more dox
  {:style/indent 1, :arglists '([model docstring? & options])}
  ;; TODO - handle docstring
  [model & args]
  (let [[docstring & options] (if (string? (first args))
                                args
                                (cons nil args))
        model-kw              (keyword (name (ns-name *ns*)) (name model))
        def-form              `(def ~model
                                 ~@(when docstring [docstring])
                                 (instance/of ~model-kw))]
    `(do
       ~def-form
       (options/defmodel-options ~model-kw [~@(options/resolve-options model-kw options)]))))


;;;                                                  CRUD functions
;;; ==================================================================================================================

;; All of `pre-*` and `post-*` multimethods are applied using `dispatch/combined-method`, and have no default
;; implementations; you can provide an implementation for `:default` to do `pre-*` or `post-*` behavior for *all*
;; models, if you are so inclined

(defmulti pre-select
  ;; TODO - dox
  {:arglists '([model honeysql-form])}
  dispatch/dispatch-value)

(defmulti post-select
  "Called by `select` and similar functions one each row as it comes out of the database.

  Some reasons you might implement this method:

  *  Removing sensitiving fields from results
  *  Adding dynamic new fields to results

  The result of this method is passed to other implementations of `post-select` and to the caller of the original
  function (e.g. `select`).

    ;; add a dynamic `:full-name` field to Users that combines their `:first-name` and `:last-name`:
    (defmethod models/post-select User [user]
      (assoc user :full-name (str (:first-name user) \" \" (:last-name user))))

    ;; select a User
    (User 1) ; -> {:id 1, :first-name \"Cam\", :last-name \"Saul\", :name \"Cam Saul\"}"
  {:arglists '([instance])}
  dispatch/dispatch-value)

;; TODO - `do-select`

;; TODO - some sort of `in` function that could be used for either `pre-insert` or `pre-update` (?)

(defmulti pre-insert
  "Called by `insert!`, `copy!`, and (`save!` if saving a new object) *before* inserting a new object into the database.

  Some reasons you might implement this method:

  *  Validating values or checking other preconditions before inserting
  *  Providing default values for certain Fields

  The result of this method is passed to other implementations of `pre-insert` and to the low-level implementation of
  `insert!`.

    ;; validate email is valid before inserting
    (defmethod models/pre-insert User [{:keys [email], :as user}]
      (validate-email email)
      user)

    ;; add a `:created_at` timestamp to users before inserting them
    (defmethod models/post-insert User [user]
      (assoc user :created_at (java.sql.Timestamp. (System/currentTimeMillis))))"
  {:arglists '([model instance])}
  dispatch/dispatch-value)

(defmulti post-insert
  "Called by `insert!`, `copy!`, and `save!` (if saving a new object) with an object *after* it is inserted into the
  database.

  Some reasons you might implement this method:

  *  Triggering specific logic that should occur after an object is inserted

    ;; grant new users default permissions after they are created
    (defmethod models/post-insert User [user]
      (grant-default-permissions! user)
      user)"
  {:arglists '([model instance])}
  dispatch/dispatch-value)

;; TODO - `do-insert!`

(defmulti pre-update
  "Called by `update!` and `save!` (if saving an existing object) *before* updating the values of a row in the database.
  You can implement this method to do things like to

  Some reasons you might implement this method:

  *  Validating values or checking other preconditions before updating
  *  Providing default values for certain fields

  The result of this method is passed to other implementations of `pre-update` and to the low-level implementation of
  `update!`.

    ;; if updating `:email`, check that it is valid before proceeding
    (defmethod models/pre-update User [{:keys [email], :as user}]
      (when email
        (validate-email email))
      user)

    ;; add an `:updated_at` timestamp to users before updating them
    (defmethod models/post-update User [user]
      (assoc user :updated_at (java.sql.Timestamp. (System/currentTimeMillis))))"
  {:arglists '([model instance])}
  dispatch/dispatch-value)

(defmulti post-update
  "Called by `update!` and `save!` (if saving an existing object) with an object *after* it was successfully updated in
  the database.

  Some reasons you might implement this method:

  *  Triggering specific logic that should occur after an object is updated

  The result of this method is passed to other implementations of `post-update` and to the caller of `update!` or
  `save!`.

    ;; record any upadtes to Users in an audit log
    (defmethod models/post-update User [user]
      (audit-user-updated! user)
      user)"
  {:arglists '([model instance])}
  dispatch/dispatch-value)

;; TODO - `do-update!`

(defmulti pre-delete
  "Called by `delete!` and related functions for each matching object that is about to be deleted.

  Some reasons you might implement this method:

  *  Checking preconditions
  *  Deleting any objects related to this object by recursively calling `delete!`

  The output of this function is passed to other implementations of `pre-delete` and to the low-level implementation
  of `delete!`.

    ;; check that we're allowed to delete a user before proceeding with the delete
    (defmethod models/pre-delete User [user]
      (assert-allowed-to-delete user)
      user))"
  {:arglists '([model instance])}
  dispatch/dispatch-value)

(defmulti post-delete
  "Called by `delete!` and related functions for each matching object *after* it was successfully deleted.

  Some reasons you might implement this method:

  *  Triggering specific logic that should occur after an object is deleted, such as cleanup

  The output of this function is passed to other implementations of `post-delete` and to the caller of the original
  function (e.g. `delete!`).

    ;; remove a user from the email mailing list once they're deleted
    (defmethod models/post-delete User [user]
      (remove-user-from-mailing-list! user)
      user)"
  {:arglists '([model instance])}
  dispatch/dispatch-value)


;;;                                            Predefined Options & Aspects
;;; ==================================================================================================================

;;; ### `table`

(defmethod options/init! ::options/table [model {:keys [table-name]}]
  (.addMethod ^MultiFn instance/table model (constantly table-name)))

(defmethod options/aspect ::options/table [_ _] nil)


;;; ### `primary-key`

(defmulti primary-key
  "Defines the primary key(s) for this Model. Defaults to `:id`.

    (defmethod primary-key User [_]
      :email)

  You can specify a composite key by returning a vector of keys:

    (defmethod primary-key Session [_]
      [:user_id :session_token])"
  {:arglists '([model])}
  dispatch/dispatch-value)

(defmethod primary-key :default
  [_]
  :id)

(defmethod options/init! ::options/primary-key [model {pk :primary-key}]
  (.addMethod ^MultiFn primary-key model (constantly pk)))

(defmethod options/aspect ::options/primary-key [_ _] nil)

;; TODO - you could override this to validate etc
(defmulti primary-key-value
  {:arglists '([object])}
  dispatch/dispatch-value)

(defmethod primary-key-value :default
  [object]
  (let [pk-key (primary-key object)]
    (if-not (sequential? pk-key)
      (get object pk-key)
      (mapv (partial get object) pk-key))))

(defmulti primary-key-where-clause
  {:arglists '([object] [model pk-value])}
  dispatch/dispatch-value)

(defmethod primary-key-where-clause :default
  ([object]
   (primary-key-where-clause object (primary-key-value object)))

  ;; TODO - not sure if needed
  ([model pk-value]
   (let [pk-key (primary-key model)]
     (if-not (sequential? pk-key)
       [:= pk-key pk-value]
       (into [:and] (for [[k v] (zipmap pk-key pk-value)]
                      [:= k v]))))))

(defmulti assoc-primary-key-value
  {:arglists '([object primary-key-value])}
  dispatch/dispatch-value)

(defmethod assoc-primary-key-value :default
  [object primary-key-value]
  {:pre [(or (keyword? primary-key-value) (every? keyword? primary-key-value))]}
  (let [pk-key (primary-key object)]
    (if-not (sequential? pk-key)
      (assoc object pk-key primary-key-value)
      (into object (zipmap pk-key primary-key-value)))))

(defmulti dissoc-primary-key-value
  {:arglists '([object])}
  dispatch/dispatch-value)

(defmethod dissoc-primary-key-value :default
  [object]
  (let [pk-key (primary-key object)]
    (if-not (sequential? pk-key)
      (dissoc object pk-key)
      (reduce dissoc object pk-key))))


;;; ### `default-fields`

(defmethod pre-select ::options/default-fields
  [{:keys [fields]} honeysql-form]
  (merge
   {:select fields}
   honeysql-form))


;;; ### `types`

;; Model types are a easy way to define functions that should be used to transform values of a certain column
;; when they come out from or go into the database.
;;
;; For example, suppose you had a `Venue` model, and wanted the value of its `:category` column to automatically
;; be converted to a Keyword when it comes out of the DB, and back into a string when put in. You could let Toucan
;; know to take care of this by defining the model as follows:
;;
;;     (defmodel Venue :my_venue_table
;;       (types {:category :keyword})
;;
;; Whenever you fetch a Venue, Toucan will automatically apply the appropriate `:out` function for values of
;; `:category`:
;;
;;    (db/select-one Venue) ; -> {:id 1, :category :bar, ...}
;;
;; In the other direction, `insert!`, `update!`, and `save!` will automatically do the reverse, and call the
;; appropriate `type-in` implementation.
;;
;; `:keyword` is the only Toucan type defined by default, but adding more is simple.
;;
;; You can add a new type by implementing `type-in` and `type-out`:
;;
;;    ;; add a :json type (using Cheshire) will serialize objects as JSON
;;    ;; going into the DB, and deserialize JSON coming out from the DB
;;    (defmethod type-in :json
;;      [_ v]
;;      (json/generate-string v))
;;
;;    (defmethod type-out :json
;;      [_ v]
;;      (json/parse-string v keyword))
;;
;; In the example above, values of any columns marked as `:json` would be serialized as JSON before going into the DB,
;; and deserialized *from* JSON when coming out of the DB.

(defmulti type-in
  {:arglists '([type-name v])}
  dispatch/dispatch-value)

(defmulti type-out
  {:arglists '([type-name v])}
  dispatch/dispatch-value)

(defmethod post-select ::options/types
  [{:keys [types]} result]
  (reduce
   (fn [result [field type-fn]]
     (update result field (if (fn? type-fn)
                            type-fn
                            (partial type-out type-fn))))
   result
   types))

(defn types [model]
  (reduce merge (for [aspect (dispatch/aspects model)
                      :when  (= (instance/model aspect) ::options/types)]
                  (:types aspect))))


;;; #### Predefined Types

(defmethod type-in :keyword
  [_ v]
  (if (keyword? v)
    (str (when-let [keyword-ns (namespace v)]
           (str keyword-ns "/"))
         (name v))
    v))

(defmethod type-out :keyword
  [_ v]
  (if (string? v)
    (keyword v)
    v))

;; TODO - `parent`
