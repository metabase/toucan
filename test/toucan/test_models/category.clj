(ns toucan.test-models.category
  "A model with custom implementations of:

   *  `types`                       -- `:lowercase-string` for `:name`, which lowercases values coming in or out of the DB;
   *  `pre-update` and `pre-insert` -- which check that a parent Category exists when setting `:parent-category-id`;
   *  `post-insert`                 -- which adds IDs of newly created Categories to a \"moderation queue\";
   *  `pre-delete`                  -- which deletes child Categories when deleting a Category;"
  (:require [clojure.string :as s]
            (toucan [db :as db]
                    [models :as models])))

(defn- maybe-lowercase-string [s]
  (when s
    (s/lower-case s)))

;; define a new custom type that will automatically lowercase strings coming into or out of the DB
(models/add-type! :lowercase-string
  :in  maybe-lowercase-string
  :out maybe-lowercase-string)


(models/defmodel Category :categories)


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


(extend (class Category)
  models/IModel
  (merge models/IModelDefaults {:types       (constantly {:name :lowercase-string})
                                :pre-insert  assert-parent-category-exists
                                :post-insert add-category-to-moderation-queue!
                                :pre-update  assert-parent-category-exists
                                :pre-delete  delete-child-categories}))
