(ns toucan.models-test
  (:require [expectations :refer [expect]]
            [toucan
             [compile :as compile]
             [db :as db]
             [dispatch :as dispatch]
             [models :as models]
             [operations :as ops]
             [test-models :as m]
             [test-setup :as test]]
            [toucan.test-models.category :as category]))

;; Test types (keyword)

;; :bar should come back as a Keyword even though it's a VARCHAR in the DB, just like :name
(expect
 {:category :bar, :name "Tempest", :id 1}
 (db/select-one m/Venue :id 1))

;; should still work when not fetching whole object
(expect
 :bar
 (db/select-one-field :category m/Venue :id 1))

;; should also work when setting values
(expect
 {:category :dive-bar, :name "Tempest", :id 1}
 (test/with-clean-db
   (db/update! m/Venue 1 :category :dive-bar)
   (db/select-one m/Venue :id 1)))

;; Make sure namespaced keywords are preserved
(expect
 {:category :bar/dive-bar, :name "Tempest", :id 1}
 (test/with-clean-db
   (db/update! m/Venue 1 :category :bar/dive-bar)
   (db/select-one m/Venue :id 1)))

;; Test custom types. m/Category.name is a custom type,:lowercase-string, that automatically lowercases strings as they
;; come in
(expect
 {:id 5, :name "wine-bar", :parent-category-id nil}
 (test/with-clean-db
   (db/insert! m/Category, :name "Wine-Bar")))

(expect
 {:id 1, :name "bar-or-club", :parent-category-id nil}
 (test/with-clean-db
   (db/update! m/Category 1, :name "Bar-Or-Club")
   (m/Category 1)))

;; Test properties (custom property -- timestamp)
(defn- timestamp-after? [^java.sql.Timestamp original, ^java.sql.Timestamp newer]
  (when (and original newer)
    (> (.getTime newer)
       (.getTime original))))

(def ^:private timestamp-after-jan-first? (partial timestamp-after? test/jan-first-2017))

;; calling insert! for m/Venue should trigger the :timestamped? :insert function
;; which should set an appropriate :created-at and :updated-at value
(expect
 {:created-at true, :updated-at true}
 (test/with-clean-db
   (db/insert! m/Venue, :name "Zeitgeist", :category "bar")
   (-> (db/select-one [m/Venue :created-at :updated-at] :name "Zeitgeist")
       (update :created-at timestamp-after-jan-first?)
       (update :updated-at timestamp-after-jan-first?))))

;; calling update! for m/Venue should trigger the :timestamped? :insert function
;; which in this case updates :updated-at (but not :created-at)
(expect
 (test/with-clean-db
   (let [venue (db/insert! m/Venue, :name "Zeitgeist", :category "bar")]
     (Thread/sleep 1000)
     (db/update! m/Venue (:id venue) :category "dive-bar"))
   (let [{:keys [created-at updated-at]} (db/select-one [m/Venue :created-at :updated-at] :name "Zeitgeist")]
     (timestamp-after? created-at updated-at))))

;; Test pre-insert

;; for m/Category, we set up `pre-insert` and `pre-update` to assert that a m/Category with `parent-category-id` exists
;; before setting it.

(expect
 AssertionError
 (test/with-clean-db
   (db/insert! m/Category :name "seafood", :parent-category-id 100)))

(expect
 {:id 5, :name "seafood", :parent-category-id 1}
 (test/with-clean-db
   (db/insert! m/Category :name "seafood", :parent-category-id 1)))

;; Test pre-update
(expect
 AssertionError
 (test/with-clean-db
   (db/update! m/Category 2
     :parent-category-id 100)))

(expect
 (test/with-clean-db
   (db/update! m/Category 2
     :parent-category-id 4)))

;; Test post-insert Categories adds the IDs of recently created Categories to a "moderation queue" as part of its
;; `post-insert` implementation; check that creating a new m/Category results in the ID of the new m/Category being at the
;; front of the queue
(expect
 5
 (test/with-clean-db
   (reset! category/categories-awaiting-moderation (clojure.lang.PersistentQueue/EMPTY))
   (db/insert! m/Category :name "toucannery")
   (peek @category/categories-awaiting-moderation)))

;; TODO - Test post-select

;; Test post-update Categories adds the IDs of recently updated Categories to a "update queue" as part of its
;; `post-update` implementation; check that updating a m/Category results in the ID of the updated m/Category being at the
;; front of the queue
(expect
  2
  (test/with-clean-db
    (reset! category/categories-recently-updated (clojure.lang.PersistentQueue/EMPTY))
    (db/update! m/Category 2 :name "lobster")
    (peek @category/categories-recently-updated)))

(expect
  [1 2]
  (test/with-clean-db
    (reset! category/categories-recently-updated (clojure.lang.PersistentQueue/EMPTY))
    (db/update! m/Category 1 :name "fine-dining")
    (db/update! m/Category 2 :name "steak-house")
    @category/categories-recently-updated))

;; Test pre-delete
;; For m/Category, deleting a parent category should also delete any child categories.
(expect
 #{{:id 3, :name "resturaunt", :parent-category-id nil}
   {:id 4, :name "mexican-resturaunt", :parent-category-id 3}}
 (test/with-clean-db
   (db/delete! m/Category :id 1)
   (set (m/Category))))

;; shouldn't delete anything else if the m/Category is not parent of anybody else
(expect
 #{{:id 1, :name "bar", :parent-category-id nil}
   {:id 3, :name "resturaunt", :parent-category-id nil}
   {:id 4, :name "mexican-resturaunt", :parent-category-id 3}}
 (test/with-clean-db
   (db/delete! m/Category :id 2)
   (set (m/Category))))

;;; ## `default-fields`

(models/defmodel UserWithDefaultFields
  (default-fields #{:first-name :last-name}))

;; if there is not `currently` a `:select` clause in HoneySQL query, `default-fields` should add it to the HoneySQL
;; query
(expect
 {:select [:first-name :last-name]}
 ((dispatch/combined-method [ops/advice :operation/select :advice/before] UserWithDefaultFields) {}))

;; if there is already a `:select` clause, `default-fields` should leave HoneySQL query as-is
(expect
 {:select [:email]}
 ((dispatch/combined-method [ops/advice :operation/select :advice/before] UserWithDefaultFields) {:select [:email]}))

;; Test default-fields
;; by default m/Venue doesn't return :created-at or :updated-at
(expect
 {:category :bar, :name "Tempest", :id 1}
 (db/select-one m/Venue :id 1))

;; check that we can still override default-fields
(expect
 {:created-at test/jan-first-2017}
 (db/select-one [m/Venue :created-at] :id 1))

;; Test using model in HoneySQL form
(expect
 [{:id 1, :name "Tempest"}
  {:id 2, :name "Ho's Tavern"}
  {:id 3, :name "BevMo"}]
 (ops/query
  {:select   [:id :name]
   :from     [m/Venue]
   :order-by [:id]}))

;; Test (empty)
(expect
 {}
 (empty (m/Venue :name "BevMo")))

;; model with multiple primary keys
(expect
 [1 2]
 (with-redefs [compile/primary-key (constantly [:a :b])]
   (compile/primary-key-value {:a 1, :b 2, :c 3})))

(expect
 [:and [:= :a 1] [:= :b 2]]
 (with-redefs [compile/primary-key (constantly [:a :b])]
   (compile/primary-key-where-clause {:a 1, :b 2, :c 3})))


;;;                                            Pre-definied aspects: types
;;; ==================================================================================================================

(expect
 {:count 1}
 (let [model [:toucan.models/types {:count (fnil inc 0)}]]
   (ops/advice :operation/select :advice/after model {})))

(models/defmodel ModelWithInlineType
  (types {:count (fnil inc 0)}))

(expect
 {:count 1}
 (ops/advice :operation/select :advice/after ModelWithInlineType {}))

(defmethod models/type-in ::json [_ v]
  (list 'json v))

(defmethod models/type-out ::json [_ v]
  (list 'parse-json v))

(expect
 {:query '(parse-json "{\"json\":true}")}
 (let [model [:toucan.models/types {:query ::json}]]
   (ops/advice :operation/select :advice/after model {:query "{\"json\":true}"})))

(models/defmodel ModelWithNamedType
  (types {:query ::json}))

(expect
 {:query '(parse-json "{\"json\":true}")}
 (ops/apply-advice :operation/select :advice/after (ops/default-strategy-for-operation :operation/select) ModelWithNamedType {:query "{\"json\":true}"}))

;; TODO - e2e tests using `select` for both Models
;; TODO - tests for pre-insert or somthing else testing `in`
