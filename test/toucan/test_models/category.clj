(ns toucan.test-models.category
  "A model with custom implementations of:

   *  `types`                       -- `:lowercase-string` for `:name`, which lowercases values coming in or out of
                                       the DB;
   *  `pre-update` and `pre-insert` -- which check that a parent Category exists when setting `:parent-category-id`;
   *  `post-insert`                 -- which adds IDs of newly created Categories to a \"moderation queue\";
   *  `pre-delete`                  -- which deletes child Categories when deleting a Category;"
  (:require [clojure.string :as str]
            [toucan
             [db :as db]
             [models :as models]]))

(defn- maybe-lowercase-string [s]
  (some-> s str/lower-case))

;; define a new custom type that will automatically lowercase strings coming into or out of the DB
(defmethod models/type-in ::lowercase-string [_ v]
  (maybe-lowercase-string v))

(defmethod models/type-out ::lowercase-string [_ v]
  (maybe-lowercase-string v))

(models/defmodel Category
  (table :categories)
  (types {:name ::lowercase-string}))

(defn- assert-parent-category-exists [{:keys [parent-category-id], :as category}]
  (when parent-category-id
    (assert (db/exists? Category :id parent-category-id)
      (format "A category with ID %d does not exist." parent-category-id)))
  category)

(defn- delete-child-categories [{:keys [id]}]
  (db/delete! Category :parent-category-id id))

(def categories-awaiting-moderation
  "A poor man's message queue of newly added Category IDs that are \"awating moderation\"."
  (atom (clojure.lang.PersistentQueue/EMPTY)))

(defn add-category-to-moderation-queue! [{:keys [id], :as new-category}]
  (swap! categories-awaiting-moderation conj id)
  new-category)

(def categories-recently-updated
  "A simple queue of recently updated Category IDs."
  (atom (clojure.lang.PersistentQueue/EMPTY)))

(defn add-category-to-updated-queue! [{:keys [id]}]
  (swap! categories-recently-updated conj id))

(defmethod models/pre-insert Category
  [_ category]
  (assert-parent-category-exists category)
  category)

(defmethod models/post-insert Category
  [_ category]
  (add-category-to-moderation-queue! category)
  category)

(defmethod models/pre-update Category
  [_ category]
  (assert-parent-category-exists category)
  category)

(defmethod models/pre-delete Category
  [_ category]
  (delete-child-categories category)
  category)
