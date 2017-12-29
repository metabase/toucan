(ns toucan.models-test
  (:require [expectations :refer :all]
            [toucan
             [db :as db]
             [test-setup :as test]]
            [toucan.test-models
             [category :as category :refer [Category]]
             [venue :refer [map->VenueInstance Venue]]]))

;; Test types (keyword)

;; :bar should come back as a Keyword even though it's a VARCHAR in the DB, just like :name
(expect
 #toucan.test_models.venue.VenueInstance{:category :bar, :name "Tempest", :id 1}
 (db/select-one Venue :id 1))

;; should still work when not fetching whole object
(expect
 :bar
 (db/select-one-field :category Venue :id 1))

;; should also work when setting values
(expect
 #toucan.test_models.venue.VenueInstance{:category :dive-bar, :name "Tempest", :id 1}
 (test/with-clean-db
   (db/update! Venue 1 :category :dive-bar)
   (db/select-one Venue :id 1)))

;; Make sure namespaced keywords are preserved
(expect
 #toucan.test_models.venue.VenueInstance{:category :bar/dive-bar, :name "Tempest", :id 1}
 (test/with-clean-db
   (db/update! Venue 1 :category :bar/dive-bar)
   (db/select-one Venue :id 1)))

;; Test custom types. Category.name is a custom type,:lowercase-string, that automatically lowercases strings as they
;; come in
(expect
 #toucan.test_models.category.CategoryInstance{:id 5, :name "wine-bar", :parent-category-id nil}
 (test/with-clean-db
   (db/insert! Category, :name "Wine-Bar")))

(expect
 #toucan.test_models.category.CategoryInstance{:id 1, :name "bar-or-club", :parent-category-id nil}
 (test/with-clean-db
   (db/update! Category 1, :name "Bar-Or-Club")
   (Category 1)))

;; Test properties (custom property -- timestamp)
(defn- timestamp-after? [^java.sql.Timestamp original, ^java.sql.Timestamp newer]
  (when (and original newer)
    (> (.getTime newer)
       (.getTime original))))

(def ^:private timestamp-after-jan-first? (partial timestamp-after? test/jan-first-2017))

;; calling insert! for Venue should trigger the :timestamped? :insert function
;; which should set an appropriate :created-at and :updated-at value
(expect
 #toucan.test_models.venue.VenueInstance{:created-at true, :updated-at true}
 (test/with-clean-db
   (db/insert! Venue, :name "Zeitgeist", :category "bar")
   (-> (db/select-one [Venue :created-at :updated-at] :name "Zeitgeist")
       (update :created-at timestamp-after-jan-first?)
       (update :updated-at timestamp-after-jan-first?))))

;; calling update! for Venue should trigger the :timestamped? :insert function
;; which in this case updates :updated-at (but not :created-at)
(expect
 (test/with-clean-db
   (let [venue (db/insert! Venue, :name "Zeitgeist", :category "bar")]
     (Thread/sleep 1000)
     (db/update! Venue (:id venue) :category "dive-bar"))
   (let [{:keys [created-at updated-at]} (db/select-one [Venue :created-at :updated-at] :name "Zeitgeist")]
     (timestamp-after? created-at updated-at))))

;; Test pre-insert

;; for Category, we set up `pre-insert` and `pre-update` to assert that a Category with `parent-category-id` exists
;; before setting it.

(expect
 AssertionError
 (test/with-clean-db
   (db/insert! Category :name "seafood", :parent-category-id 100)))

(expect
 #toucan.test_models.category.CategoryInstance{:id 5, :name "seafood", :parent-category-id 1}
 (test/with-clean-db
   (db/insert! Category :name "seafood", :parent-category-id 1)))

;; Test pre-update
(expect
 AssertionError
 (test/with-clean-db
   (db/update! Category 2
     :parent-category-id 100)))

(expect
 (test/with-clean-db
   (db/update! Category 2
     :parent-category-id 4)))

;; Test post-insert Categories adds the IDs of recently created Categories to a "moderation queue" as part of its
;; `post-insert` implementation; check that creating a new Category results in the ID of the new Category being at the
;; front of the queue
(expect
 5
 (test/with-clean-db
   (reset! category/categories-awaiting-moderation (clojure.lang.PersistentQueue/EMPTY))
   (db/insert! Category :name "toucannery")
   (peek @category/categories-awaiting-moderation)))

;; TODO - Test post-select

;; Test post-update Categories adds the IDs of recently updated Categories to a "update queue" as part of its
;; `post-update` implementation; check that updating a Category results in the ID of the updated Category being at the
;; front of the queue
(expect
  2
  (test/with-clean-db
    (reset! category/categories-recently-updated (clojure.lang.PersistentQueue/EMPTY))
    (db/update! Category 2 :name "lobster")
    (peek @category/categories-recently-updated)))

(expect
  [1 2]
  (test/with-clean-db
    (reset! category/categories-recently-updated (clojure.lang.PersistentQueue/EMPTY))
    (db/update! Category 1 :name "fine-dining")
    (db/update! Category 2 :name "steak-house")
    @category/categories-recently-updated))

;; Test pre-delete
;; For Category, deleting a parent category should also delete any child categories.
(expect
 #{#toucan.test_models.category.CategoryInstance{:id 3, :name "resturaunt", :parent-category-id nil}
   #toucan.test_models.category.CategoryInstance{:id 4, :name "mexican-resturaunt", :parent-category-id 3}}
 (test/with-clean-db
   (db/delete! Category :id 1)
   (set (Category))))

;; shouldn't delete anything else if the Category is not parent of anybody else
(expect
 #{#toucan.test_models.category.CategoryInstance{:id 1, :name "bar", :parent-category-id nil}
   #toucan.test_models.category.CategoryInstance{:id 3, :name "resturaunt", :parent-category-id nil}
   #toucan.test_models.category.CategoryInstance{:id 4, :name "mexican-resturaunt", :parent-category-id 3}}
 (test/with-clean-db
   (db/delete! Category :id 2)
   (set (Category))))

;; Test default-fields
;; by default Venue doesn't return :created-at or :updated-at
(expect
 #toucan.test_models.venue.VenueInstance{:category :bar, :name "Tempest", :id 1}
 (db/select-one Venue :id 1))

;; check that we can still override default-fields
(expect
 (map->VenueInstance {:created-at test/jan-first-2017})
 (db/select-one [Venue :created-at] :id 1))

;; Test invoking model as a function (no args)
(expect
 [#toucan.test_models.venue.VenueInstance{:category :bar,   :name "Tempest",     :id 1}
  #toucan.test_models.venue.VenueInstance{:category :bar,   :name "Ho's Tavern", :id 2}
  #toucan.test_models.venue.VenueInstance{:category :store, :name "BevMo",       :id 3}]
 (sort-by :id (Venue)))

;; Test invoking model as a function (single arg, id)
(expect
 #toucan.test_models.venue.VenueInstance{:category :bar, :name "Tempest", :id 1}
 (Venue 1))

;; Test invoking model as a function (kwargs)
(expect
 #toucan.test_models.venue.VenueInstance{:category :store, :name "BevMo", :id 3}
 (Venue :name "BevMo"))

;; Test invoking model as a function with apply
(expect
 #toucan.test_models.venue.VenueInstance{:category :store, :name "BevMo", :id 3}
 (apply Venue [:name "BevMo"]))

;; Test using model in HoneySQL form
(expect
 [{:id 1, :name "Tempest"}
  {:id 2, :name "Ho's Tavern"}
  {:id 3, :name "BevMo"}]
 (db/query {:select   [:id :name]
            :from     [Venue]
            :order-by [:id]}))

;; Test (empty)
(expect
 #toucan.test_models.venue.VenueInstance{}
 (empty (Venue :name "BevMo")))
