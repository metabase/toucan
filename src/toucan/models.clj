(ns toucan.models
  "The `defmodel` macro, used to define Toucan models, and
   the `IModel` protocol and default implementations, which implement Toucan model functionality."
  (:require [clojure.walk :refer [postwalk]]
            [honeysql.format :as hformat]
            [potemkin.types :as p.types]
            [toucan.util :as u])
  (:import honeysql.format.ToSql))

;;;                                                   Configuration
;;; ==================================================================================================================

;;; ### Root Model Namespace

;; The root model namespace is the parent namespace of all Toucan models. Toucan knows how to automatically load
;; namespaces where models live, which is handy for avoiding circular references; to facilitate this, Toucan models
;; need to live in places that match an expected pattern.
;;
;; For example, a model named `UserFollow` must live in the namespace `<root-model-namespace>.user-follow`.
;;
;; The root model namespace defaults to `models`; in the example above, `UserFollow` would live in
;; `models.user-follow`.
;;
;; This is almost certainly not what you want; set your own value by calling `set-root-namespace!`:
;;
;;     (models/set-root-namespace! 'my-project.models)
;;
;; After setting the default model root namespace as in the example above, Toucan will look for `UserFollow`
;; in `my-project.models.user-follow`.


(defonce ^:private -root-namespace (atom 'models))

(defn set-root-namespace!
  "Set the root namespace where all models are expected to live.

     (set-root-namespace! 'my-project.models)

  In this example, Toucan would look for a model named `UserFollow` in the namespace `my-project.models.user-follow`."
  [new-root-namespace]
  {:pre [(symbol? new-root-namespace)]}
  (reset! -root-namespace new-root-namespace))

(defn root-namespace
  "Fetch the parent namespace for all Toucan models."
  []
  @-root-namespace)


;;; ### Types

;; Model types are a easy way to define functions that should be used to transform values of a certain column
;; when they come out from or go into the database.
;;
;; For example, suppose you had a `Venue` model, and wanted the value of its `:category` column to automatically
;; be converted to a Keyword when it comes out of the DB, and back into a string when put in. You could let Toucan
;; know to take care of this by defining the model as follows:
;;
;;     (defmodel Venue :my_venue_table)
;;
;;     (extend (class Venue)
;;       models/IModel
;;       (merge models/IModelDefaults
;;              {:types (constantly {:category :keyword})}))
;;
;; Whenever you fetch a Venue, Toucan will automatically apply the appropriate `:out` function for values of
;; `:category`:
;;
;;     (db/select-one Venue) ; -> {:id 1, :category :bar, ...}
;;
;; In the other direction, `insert!` and `update!` will automatically do the reverse, and call the appropriate `:in`
;; function.
;;
;; `:keyword` is the only Toucan type defined by default, but adding more is simple.
;;
;; You can add a new type by calling `add-type!`:
;;
;;     ;; add a :json type (using Cheshire) will serialize objects as JSON
;;     ;; going into the DB, and deserialize JSON coming out from the DB
;;     (add-type! :json
;;       :in  json/generate-string
;;       :out #(json/parse-string % keyword))
;;
;; In the example above, values of any columns marked as `:json` would be serialized as JSON before going into the DB,
;; and deserialized *from* JSON when coming out of the DB.

(defonce ^:private type-fns
  (atom {:keyword {:in  u/keyword->qualified-name
                   :out keyword}}))

(defn add-type!
  "Add a new type mapping for type named by key K. Supply mappings for the functions that should prepare value
   when it goes `:in` to the database, and for when it comes `:out`.

     ;; add a :json type (using Cheshire) will serialize objects as JSON
     ;; going into the DB, and deserialize JSON coming out from the DB
     (add-type! :json
       :in  json/generate-string
       :out #(json/parse-string % keyword))"
  {:style/indent 1}
  [k & {:keys [in out]}]
  {:pre [(fn? in) (fn? out)]}
  (swap! type-fns assoc k {:in in, :out out}))


;;; ### Properties

;; Model properties are a powerful way to extend the functionality of Toucan models.
;;
;; With properties, you can define custom functions that can modify the values (or even add new ones) of an object
;; before it is saved (via the `insert!` and `update!` family of functions) or when it comes out of the DB (via the
;; `select` family of functions).
;;
;; Properties are global, which lets you define a single set of functions that can be applied to multiple models
;; that have the same property, without having to define repetitive code in model methods such as `pre-insert!`.
;;
;; For example, suppose you have several models with `:created-at` and `:updated-at` columns. Whenever a new instance
;; of these models is inserted, you want to set `:created-at` and `:updated-at` to be the current time; whenever an
;; instance is updated, you want to update `:updated-at`.
;;
;; You *could* handle this behavior by defining custom implementations for `pre-insert` and `pre-update` for each of
;; these models, but that gets repetitive quickly. Instead, you can simplfy this behavior by defining a new *property*
;; that can be shared by multiple models:
;;
;;     (add-property! :timestamped?
;;       :insert (fn [obj _]
;;                 (let [now (java.sql.Timestamp. (System/currentTimeMillis))]
;;                   (assoc obj :created-at now, :updated-at now)))
;;       :update (fn [obj _]
;;                 (assoc obj :updated-at (java.sql.Timestamp. (System/currentTimeMillis)))))
;;
;;     (defmodel Venue :my_venue_table)
;;
;;     (extend (class Venue)
;;       models/IModel
;;       (merge models/IModelDefaults
;;              {:properties (constantly {:timestamped? true})}))
;;
;; In this example, before a Venue is inserted, a new value for `:created-at` and `:updated-at` will be added; before
;; one is updated, a new value for `:updated-at` will be added.
;;
;; Property functions can be defined for any combination of `:insert`, `:update`, and `:select`.
;; If these functions are defined, they will be called as such:
;;
;;     (fn [object property-value])
;;
;; where `property-value` is the value for the key in question returned by the model's implementation of `properties`.
;;
;; In the example above, `:timestamped?` is set to `true` for `Venue`; since we're not interested in the value in the
;; example above we simply ignore it (by binding it to `_`).
;;
;; You can set the value to any truthy value you'd like, which can be used to customize behavior for different models,
;; making properties even more flexible.

(defonce ^:private property-fns (atom nil))

(defn add-property!
  "Define a new model property and set the functions used to implement its functionality.
   See documentation for more details.

     (add-property! :timestamped?
       :insert (fn [obj _]
                 (let [now (java.sql.Timestamp. (System/currentTimeMillis))]
                   (assoc obj :created-at now, :updated-at now)))
       :update (fn [obj _]
                 (assoc obj :updated-at (java.sql.Timestamp. (System/currentTimeMillis)))))"
  {:style/indent 1}
  [k & {:keys [insert update select]}]
  {:pre [(or (not insert) (fn? insert))
         (or (not update) (fn? update))
         (or (not select) (fn? select))]}
  (swap! property-fns assoc k {:insert insert, :update update, :select select}))


;;;                                                 IModel Interface
;;; ==================================================================================================================

(p.types/defprotocol+ IModel
  "The `IModel` protocol defines the various methods that are used to provide custom behavior for various models.

   This protocol contains the various methods model classes can optionally implement. All methods have a default
   implementation provided by `IModelDefaults`; new models created with the `defmodel` macro automatically implement
   this protocol using those default implementations. To override one or more implementations, use `extend` and
   `merge` your custom implementations with `IModelDefaults`:

     (defmodel MyModel)

     (extend (class MyModel)
       IModel
       (merge IModelDefaults {...}))"

  (pre-insert [this]
    "Gets called by `insert!` immediately before inserting a new object.
     This provides an opportunity to do things like encode JSON or provide default values for certain fields.

         (pre-insert [query]
           (let [defaults {:version 1}]
             (merge defaults query))) ; set some default values")

  ;TODO add support for composite keys
  (primary-key ^clojure.lang.Keyword [this]
    "Defines the primary key. Defaults to :id

        (primary-key [_] :id)

    NOTE: composite keys are currently not supported")

  (post-insert [this]
    "Gets called by `insert!` with an object that was newly inserted into the database.
     This provides an opportunity to trigger specific logic that should occur when an object is inserted or modify the
     object that is returned. The value returned by this method is returned to the caller of `insert!`. The default
     implementation is `identity`.

       (post-insert [user]
         (assoc user :newly-created true))

       (post-insert [user]
         (add-user-to-magic-perm-groups! user)
         user)")

  (pre-update [this]
    "Called by `update!` before DB operations happen. A good place to set updated values for fields like `updated-at`,
     or to check preconditions.")

  (post-update [this]
    "Gets called by `update!` with an object that was successfully updated in the database.
     This provides an opportunity to trigger specific logic that should occur when an object is updated.
     The value returned by this method is not returned to the caller of `update!`. The default
     implementation is `nil` (not invoked).

     Note: This method is *not* invoked when calling `update!` with a `honeysql-form` form.

       (post-update [user]
         (audit-user-updated! user)")

  (post-select [this]
    "Called on the results from a call to `select` and similar functions. Default implementation doesn't do anything,
     but you can provide custom implementations to do things like remove sensitive fields or add dynamic new ones.

  For example, let's say we want to add a `:name` field to Users that combines their `:first-name` and `:last-name`:

      (defn- post-select [user]
        (assoc user :name (str (:first-name user) \" \" (:last-name user))))

  Then, when we select a User:

      (User 1) ; -> {:id 1, :first-name \"Cam\", :last-name \"Saul\", :name \"Cam Saul\"}")

  (pre-delete [this]
    "Called by `delete!` for each matching object that is about to be deleted.
     Implementations can delete any objects related to this object by recursively calling `delete!`, or do
     any other cleanup needed, or check some preconditions that must be fulfilled before deleting an object.

  The output of this function is ignored.

        (pre-delete [{database-id :id :as database}]
          (delete! Card :database_id database-id)
          ...)")

  (default-fields ^clojure.lang.Sequential [this]
    "Return a sequence of keyword field names that should be fetched by default when calling
     `select` or invoking the model (e.g., `(Database 1)`).")

  (hydration-keys ^clojure.lang.Sequential [this]
    "The `hydration-keys` method can be overrode to specify the keyword field names that should be hydrated
     as instances of this model. For example, `User` might include `:creator`, which means `hydrate` will
     look for `:creator_id` or `:creator-id` in other objects to find the User ID, and fetch the `Users`
     corresponding to those values.")

  (types ^clojure.lang.IPersistentMap [this]
    "Return a map of keyword field names to their types for fields that should be serialized/deserialized in a special
     way. Values belonging to a type are sent through an input function before being inserted into the DB, and sent
     through an output function on their way out. `:keyword` is the only type enabled by default; you can add more by
     calling `add-type!`:

       (add-type! :json, :in json/generate-string, :out json/parse-string)

   Set the types for a model like so:

       ;; convert `:category` to a keyword when it comes out of the DB; convert back to a string before going in
       (types [_] {:category :keyword})")

  (properties ^clojure.lang.IPersistentMap [this]
    "Return a map of properties of this model. Properties can be used to implement advanced behavior across many
     different models; see the documentation for more details. Declare a model's properties as such:

       (properties [_] {:timestamped? true})

  Define functions to handle objects with those properties using `add-property!`:

      (add-property! :timestamped?
        :insert (fn [obj] (assoc obj :created-at (new-timestamp), :updated-at (new-timestamp)))
        :update (fn [obj] (assoc obj :updated-at (new-timestamp))))"))


;;;                                                   INTERNAL IMPL
;;; ==================================================================================================================

(defn- apply-type-fns
  "Apply the appropriate `type-fns` for an `obj` implementing some `IModel`."
  [obj direction]
  (into obj (for [[col type] (types obj)]
              (when-some [v (get obj col)]
                {col ((get-in @type-fns [type direction]) v)}))))

(defn- apply-property-fns
  [context obj]
  (loop [obj obj, [[k v] & more] (seq (properties obj))]
    (let [f (get-in @property-fns [k context])]
      (cond
        (not k) obj
        f       (recur (f obj v) more)
        :else   (recur obj       more)))))


(p.types/defprotocol+ ICreateFromMap
  "Used by internal functions like `do-post-select`."
  (^:private map-> [klass, ^clojure.lang.IPersistentMap m]
   "Convert map M to instance of record type KLASS."))

;; these functions call (map-> model ...) twice to make sure functions like pre-insert/post-select
;; didn't do something that accidentally removed the typing

(defn do-pre-insert
  "Don't call this directly! Apply functions like `pre-insert` before inserting an object into the DB."
  [model obj]
  (as-> obj <>
    (map-> model <>)
    (pre-insert <>)
    (map-> model <>)
    (apply-type-fns <> :in)
    (apply-property-fns :insert <>)))

(defn do-pre-update
  "Don't call this directly! Apply internal functions like `pre-update` before updating an object in the DB."
  [model obj]
  (as-> obj <>
    (map-> model <>)
    (pre-update <>)
    (map-> model <>)
    (apply-type-fns <> :in)
    (apply-property-fns :update <>)))

(defn do-post-select
  "Don't call this directly! Apply internal functions like `post-select` when an object is retrieved from the DB."
  [model obj]
  (as-> obj <>
    (map-> model <>)
    (apply-type-fns <> :out)
    (post-select <>)
    (map-> model <>)
    (apply-property-fns :select <>)))

(defn do-type-fn
  "Don't call this directly! This is automatically applied when doing select."
  [model col v direction]
  (if-let [type (col (types model))]
    ((get-in @type-fns [type direction]) v)
    v))

(def IModelDefaults
  "Default implementations for `IModel` methods."
  {:default-fields (constantly nil)
   :primary-key    (constantly :id)
   :types          (constantly nil)
   :properties     (constantly nil)
   :pre-insert     identity
   :post-insert    identity
   :pre-update     identity
   :post-update    nil
   :post-select    identity
   :pre-delete     (constantly nil)
   :hydration-keys (constantly nil)})

(defn- invoke-model
  "Fetch an object with a specific ID or all objects of type ENTITY from the DB.

     (invoke-model Database)           -> seq of all databases
     (invoke-model Database 1)         -> Database w/ ID 1
     (invoke-model Database :id 1 ...) -> A single Database matching some key-value args"
  ([model]
   ((resolve 'toucan.db/select) model))
  ([model id]
   (when id
     (invoke-model model (primary-key model) id)))
  ([model k v & more]
   (apply (resolve 'toucan.db/select-one) model k v more)))

(defn model?
  "Is model a valid toucan model?"
  [model]
  (and (record? model)
       (::model model)))

;; We use the same record type (e.g., `DatabaseInstance`) for both the "model" (e.g., `Database`) and objects fetched
;; from the DB ("instances"). Model definitions have the key `::model` assoced so we can differentiate.
;; Invoking an instance calls get so you can do things like `(db :name)` as if it were a regular map.
(defn invoke-model-or-instance
  "Check whether OBJ is an model (e.g. `Database`) or an object from the DB; if an model,
   call `invoked-model`; otherwise call `get`."
  [obj & args]
  (apply (if (model? obj)
           invoke-model
           get)
         obj args))


;;;                                                   DEFMODEL MACRO
;;; ==================================================================================================================

(defn- ifn-invoke-forms
  "Macro helper, generates

       (~'invoke [this#]
        (invoke-model-or-instance this#))
       (~'invoke [this# id#]
        (invoke-model-or-instance this# id#))
       (~'invoke [this# arg1# arg2#]
        (invoke-model-or-instance this# arg1# arg2#))
       ,,,"
  []
  (let [args (map #(symbol (str "arg" %)) (range 1 19))
        arg-lists (reductions conj ['this] args)]
    (for [l arg-lists]
      (list 'invoke l (concat `(invoke-model-or-instance) l)))))

(defn- fully-qualified-symbol
  "Make a symbol fully qualified by resolving it as a var in the current namespace.

     conj        ;;=> clojure.core/conj
     str/join    ;;=> clojure.string/join
     foo.bar/baz ;;=> foo.bar/baz "
  [s]
  (let [{:keys [ns name]} (meta (resolve s))]
    (symbol (str ns "/" name))))

(defn- method-forms-map
  "Take in forms as passed to defrecord or extend-type (protocol or interface
  name followed by method definitions), and return a map suitable for use with
  extend.

    (IFn (invoke [this] this)) ;;=> {IFn {:invoke (fn [this] this)}}"
  [forms]
  (second
   (reduce (fn [[type acc] form]
             (if (symbol? form)
               [(fully-qualified-symbol form) acc]
               [type (assoc-in acc [type (keyword (first form))] `(fn ~@(drop 1 form)))])) [nil {}] forms)))

(defonce
  ^{:doc "Mapping from model name to namespace containing the model. Useful in order to resolve models which
  are not defined in a namespace matching the convention."}
  model-sym->namespace-sym
  (atom {}))

(defmacro defmodel
  "Define a new \"model\". Models encapsulate information and behaviors related to a specific table in the application
  DB, and have their own unique record type.

  `defmodel` defines a backing record type following the format `<model>Instance`. For example, the class associated
  with `User` is `<root-namespace>.user/UserInstance`. (The root namespace defaults to `models` but can be configured
  via `set-root-namespace!`)

  This class is used for both the titular model (e.g. `User`) and for objects that are fetched from the DB. This means
  they can share the `IModel` protocol and simplifies the interface somewhat; functions like `types` work on either
  the model or instances fetched from the DB.

     (defmodel User :user_table)  ; creates class `UserInstance` and DB model `User`

     (db/select User, ...)  ; use with `toucan.db` functions. All results are instances of `UserInstance`

  The record type automatically extends `IModel` with `IModelDefaults`, but you can override specific methods, or
  implement other protocols, by passing them to `defmodel`, the same way you would with `defrecord`.

     (defmodel User :user_table
       IModel
       (hydration-keys [_]
         [:user])
       (properties [_]
         {:timestamped true})
       (pre-insert [user]
         user))

   This is equivalent to:

     (extend (class User)             ; it's somewhat more readable to write `(class User)` instead of `UserInstance`
       IModel (merge IModelDefaults
                      {...}))

   Finally, the model itself is invokable. Calling with no args returns *all* values of that object; calling with a
  single arg can be used to fetch a specific instance by its integer ID.

     (Database)                       ; return a seq of *all* Databases (as instances of `DatabaseInstance`)
     (Database 1)                     ; return Database 1"
  {:arglists     '([model table-name] [model docstr? table-name])
   :style/indent [2 :form :form [1]]}
  [model & args]
  (let [[docstr table-name] (if (string? (first args))
                              args
                              (list nil (first args)))
        extend-forms        (if (string? (first args))
                              (drop 2 args)
                              (drop 1 args))
        instance            (symbol (str model "Instance"))
        map->instance       (symbol (str "map->" instance))
        defrecord-form      `(p.types/defrecord+ ~instance []
                               clojure.lang.Named
                               (~'getName [~'_] ~(name model))
                               (~'getNamespace [~'_] ~(name (ns-name *ns*)))

                               clojure.lang.IFn
                               ~@(ifn-invoke-forms)
                               (~'applyTo [~'this ^clojure.lang.ISeq ~'args]
                                (apply invoke-model-or-instance ~'this ~'args)))

        ;; Replace the implementation of `empty`. It's either this, or using the
        ;; lower level `deftype`, and re-implementing all of `defrecord`
        defrecord-form (postwalk (fn [f]
                                   (if (and (seq? f) (= (first f) 'clojure.core/empty))
                                     `(empty [_#] (~map->instance {}))
                                     f))
                                 (macroexpand defrecord-form))]
    `(do
       (swap! @#'model-sym->namespace-sym assoc '~model (ns-name *ns*))
       ~defrecord-form

       (extend ~instance
         ~@(mapcat identity (merge-with (fn [this that] `(merge ~this ~that))
                              `{toucan.models/IModel         toucan.models/IModelDefaults
                                toucan.models/ICreateFromMap {:map-> (fn [~'_ & args#] (apply ~map->instance args#))}
                                honeysql.format/ToSql        {:to-sql (comp hformat/to-sql keyword :table)}}
                              (method-forms-map extend-forms))))

       (def ~(vary-meta model assoc
                        :tag      (symbol (str (namespace-munge *ns*) \. instance))
                        :arglists ''([] [id] [& kvs])
                        :doc      (or docstr
                                      (format "Entity for '%s' table; instance of %s." (name table-name) instance)))
         (~map->instance {:table  ~table-name
                          :name   ~(name model)
                          ::model true})))))
