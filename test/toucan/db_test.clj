(ns toucan.db-test
  (:require [expectations :refer :all]
            [toucan
             [db :as db]
             [test-setup :as test]]
            [toucan.test-models
             [address :refer [Address]]
             [category :refer [Category]]
             [user :refer [User]]
             [venue :refer [Venue]]]))

;; Test overriding quoting-style
(expect
 "`toucan`"
 (binding [db/*quoting-style* :mysql]
   ((db/quote-fn) "toucan")))

(expect
 "\"toucan\""
 (binding [db/*quoting-style* :ansi]
   ((db/quote-fn) "toucan")))

(expect
 "[toucan]"
 (binding [db/*quoting-style* :sqlserver]
   ((db/quote-fn) "toucan")))

;; Test allowing dashed field names
(expect
 false
 (db/automatically-convert-dashes-and-underscores?))

(expect
  (binding [db/*automatically-convert-dashes-and-underscores* true]
    (db/automatically-convert-dashes-and-underscores?)))

(expect
 false
  (binding [db/*automatically-convert-dashes-and-underscores* false]
    (db/automatically-convert-dashes-and-underscores?)))

(expect
  {:street_name "1 Toucan Drive"}
  (db/select-one [Address :street_name]))

(expect
  {:street-name "1 Toucan Drive"}
  (binding [db/*automatically-convert-dashes-and-underscores* true]
    (db/select-one [Address :street-name])))

(expect
  "1 Toucan Drive"
  (binding [db/*automatically-convert-dashes-and-underscores* true]
    (db/select-one-field :street-name Address)))

;; Test replace-underscores
(expect
 :2-cans
 (#'db/replace-underscores :2_cans))

;; shouldn't do anything to keywords with no underscores
(expect
 :2-cans
 (#'db/replace-underscores :2-cans))

;; should work with strings as well
(expect
 :2-cans
 (#'db/replace-underscores "2_cans"))

;; make sure it respects namespaced keywords or keywords with slashes in them
(expect
 :bird-types/two-cans
 (#'db/replace-underscores :bird-types/two_cans))

;; don't barf if there's a nil input
(expect
 nil
 (#'db/replace-underscores nil))

;; shouldn't do anything for numbers!
(expect
 2
 (#'db/replace-underscores 2))

;; Test transform-keys
(expect
 {:2-cans true}
 (#'db/transform-keys #'db/replace-underscores {:2_cans true}))

;; make sure it works recursively and inside arrays
(expect
 [{:2-cans {:2-cans true}}]
 (#'db/transform-keys #'db/replace-underscores [{:2_cans {:2_cans true}}]))

;; TODO - Test DB connection (how?)

;; TODO - Test overriding DB connection (how?)

;; Test transaction
(expect
 0
 ;; attempt to insert! two of the same Venues. Since the second has a duplicate name,
 ;; the whole transaction should fail, and neither should get inserted.
 (test/with-clean-db
   (try
     (db/transaction
       (db/insert! Venue :name "Cam's Toucannery", :category "Pet Store")
       (db/insert! Venue :name "Cam's Toucannery", :category "Pet Store"))
     (catch Throwable _))
   (db/count Venue :name "Cam's Toucannery")))

;; TODO - Test DB logging (how?)

;; Test resolve-model
(expect
 User
 (db/resolve-model 'User))

;; If model is already resolved it should just return it as-is
(expect
 User
 (db/resolve-model User))

;; Trying to resolve an model that cannot be found should throw an Exception
(expect
 Exception
 (db/resolve-model 'Fish))

;; ... as should trying to resolve things that aren't entities or symbols
(expect Exception (db/resolve-model {}))
(expect Exception (db/resolve-model 100))
(expect Exception (db/resolve-model "User"))
(expect Exception (db/resolve-model :user))
(expect Exception (db/resolve-model 'user)) ; entities symbols are case-sensitive

;; Test with-call-counting
(expect
 2
 (db/with-call-counting [call-count]
   (db/select-one User)
   (db/select-one User)
   (call-count)))

;; Test debug-count-calls
(expect
 "DB Calls: 2\n"
 (with-out-str (db/debug-count-calls
                 (db/select-one User)
                 (db/select-one User))))

;; TODO - Test format-sql

;; TODO - Test debug-print-queries

;; Test query
(expect
  [{:id 1, :first-name "Cam", :last-name "Saul"}]
  (db/query {:select   [:*]
             :from     [:users]
             :order-by [:id]
             :limit    1}))

(defn- transduce-to-set
  "Process `reducible-query-result` using a transducer that puts the rows from the resultset into a set"
  [reducible-query-result]
  (transduce (map identity) conj #{} reducible-query-result))

;; Test query-reducible
(expect
  #{{:id 1, :first-name "Cam", :last-name "Saul"}}
  (transduce-to-set (db/reducible-query {:select   [:*]
                                         :from     [:users]
                                         :order-by [:id]
                                         :limit    1})))

;; Test qualify
(expect
  :users.first-name
  (db/qualify User :first-name))

(expect
  :users.first-name
  (db/qualify User "first-name"))

;; test qualified?
(expect true  (db/qualified? :users.first-name))
(expect true  (db/qualified? "users.first-name"))
(expect false (db/qualified? :first-name))
(expect false (db/qualified? "first-name"))

;; Test simple-select
(expect
  [#toucan.test_models.user.UserInstance{:id 1, :first-name "Cam", :last-name "Saul"}]
  (db/simple-select User {:where [:= :id 1]}))

(expect
  [#toucan.test_models.user.UserInstance{:id 3, :first-name "Lucky", :last-name "Bird"}]
  (db/simple-select User {:where [:and [:not= :id 1] [:not= :id 2]]}))

;; Test simple-select-reducible
(expect
  #{#toucan.test_models.user.UserInstance{:id 1, :first-name "Cam", :last-name "Saul"}}
  (transduce-to-set (db/simple-select-reducible User {:where [:= :id 1]})))

;; Test simple-select-one
(expect
 #toucan.test_models.user.UserInstance{:id 1, :first-name "Cam", :last-name "Saul"}
 (db/simple-select-one User {:where [:= :first-name "Cam"]}))

;; TODO - Test execute!

;; Test update!
(expect
 #toucan.test_models.user.UserInstance{:id 1, :first-name "Cam", :last-name "Era"}
 (test/with-clean-db
   (db/update! User 1 :last-name "Era")
   (db/select-one User :id 1)))

;; Test update-where!
(expect
 [#toucan.test_models.user.UserInstance{:id 1, :first-name "Cam", :last-name "Saul"}
  #toucan.test_models.user.UserInstance{:id 2, :first-name "Cam", :last-name "Toucan"}
  #toucan.test_models.user.UserInstance{:id 3, :first-name "Cam", :last-name "Bird"}]
 (test/with-clean-db
   (db/update-where! User {:first-name [:not= "Cam"]}
     :first-name "Cam")
   (db/select User {:order-by [:id]})))

(expect
 [#toucan.test_models.user.UserInstance{:id 1, :first-name "Not Cam", :last-name "Saul"}
  #toucan.test_models.user.UserInstance{:id 2, :first-name "Rasta", :last-name "Toucan"}
  #toucan.test_models.user.UserInstance{:id 3, :first-name "Lucky", :last-name "Bird"}]
 (test/with-clean-db
   (db/update-where! User {:first-name "Cam"}
     :first-name "Not Cam")
   (db/select User {:order-by [:id]})))

;; Test update-non-nil-keys!
(expect
 #toucan.test_models.user.UserInstance{:id 2, :first-name "Rasta", :last-name "Can"}
 (test/with-clean-db
   (db/update-non-nil-keys! User 2
     :first-name nil
     :last-name "Can")
   (User 2)))

(expect
 #toucan.test_models.user.UserInstance{:id 2, :first-name "Rasta", :last-name "Can"}
 (test/with-clean-db
   (db/update-non-nil-keys! User 2
     {:first-name nil
      :last-name  "Can"})
   (User 2)))

;; TODO - Test simple-delete!

;;; Test simple-insert-many!

;; It must return the inserted ids
(expect
 [5]
 (test/with-clean-db
   (db/simple-insert-many! Category [{:name "seafood" :parent-category-id 100}])))

;; it must not fail when using SQL function calls.
(expect
 [4 5]
 (test/with-clean-db
   (db/simple-insert-many! User [{:first-name "Grass" :last-name #sql/call [:upper "Hopper"]}
                                 {:first-name "Ko" :last-name "Libri"}])))

;;; Test insert-many!

;; It must return the inserted ids, it must not fail when using SQL function calls.
(expect
 [4 5]
 (test/with-clean-db
   (db/insert-many! User [{:first-name "Grass" :last-name #sql/call [:upper "Hopper"]}
                          {:first-name "Ko" :last-name "Libri"}])))

;; It must call pre-insert hooks
(expect
 AssertionError ; triggered by pre-insert hook
 (test/with-clean-db
   (db/insert-many! Category [{:name "seafood" :parent-category-id 100}])))

;; TODO - Test simple-insert!

;;; Test insert!

;; It must return the inserted model
(expect
 #toucan.test_models.user.UserInstance{:id 4, :first-name "Trash", :last-name "Bird"}
 (test/with-clean-db
   (db/insert! User {:first-name "Trash", :last-name "Bird"})))

;; The returned data must match what's been inserted in the table
(expect
 #toucan.test_models.user.UserInstance{:id 4, :first-name "Grass", :last-name "HOPPER"}
 (test/with-clean-db
   (db/insert! User {:first-name "Grass" :last-name #sql/call [:upper "Hopper"]})))

;; Test select-one
(expect
  #toucan.test_models.user.UserInstance{:id 1, :first-name "Cam", :last-name "Saul"}
  (db/select-one User, :first-name "Cam"))

(expect
  #toucan.test_models.user.UserInstance{:id 3, :first-name "Lucky", :last-name "Bird"}
  (db/select-one User {:order-by [[:id :desc]]}))

(expect
  #toucan.test_models.user.UserInstance{:first-name "Lucky", :last-name "Bird"}
  (db/select-one [User :first-name :last-name] {:order-by [[:id :desc]]}))

;; Test select-one-field
(expect
  "Cam"
  (db/select-one-field :first-name User, :id 1))

(expect
  1
  (db/select-one-field :id User, :first-name "Cam"))

;; Test select-one-id
(expect
  1
  (db/select-one-id User, :first-name "Cam"))

;; Test count
(expect
  3
  (db/count User))

(expect
  1
  (db/count User, :first-name "Cam"))

(expect
  2
  (db/count User, :first-name [:not= "Cam"]))

;; Test select
(expect
  [#toucan.test_models.user.UserInstance{:id 1, :first-name "Cam",   :last-name "Saul"}
   #toucan.test_models.user.UserInstance{:id 2, :first-name "Rasta", :last-name "Toucan"}
   #toucan.test_models.user.UserInstance{:id 3, :first-name "Lucky", :last-name "Bird"}]
  (db/select User {:order-by [:id]}))

(expect
  [#toucan.test_models.user.UserInstance{:id 2, :first-name "Rasta", :last-name "Toucan"}
   #toucan.test_models.user.UserInstance{:id 3, :first-name "Lucky", :last-name "Bird"}]
  (db/select User
    :first-name [:not= "Cam"]
    {:order-by [:id]}))

(expect
  [#toucan.test_models.user.UserInstance{:first-name "Cam",   :last-name "Saul"}
   #toucan.test_models.user.UserInstance{:first-name "Rasta", :last-name "Toucan"}
   #toucan.test_models.user.UserInstance{:first-name "Lucky", :last-name "Bird"}]
  (db/select [User :first-name :last-name] {:order-by [:id]}))

;; Check that `select` works as we'd expect with where clauses with more than two arguments, for example BETWEEN
(expect
  [#toucan.test_models.user.UserInstance{:first-name "Cam",   :last-name "Saul"}
   #toucan.test_models.user.UserInstance{:first-name "Rasta", :last-name "Toucan"}]
  (db/select [User :first-name :last-name] :id [:between 1 2] {:order-by [:id]}))

;; Test select-reducible
(expect
  #{#toucan.test_models.user.UserInstance{:id 1, :first-name "Cam",   :last-name "Saul"}
    #toucan.test_models.user.UserInstance{:id 2, :first-name "Rasta", :last-name "Toucan"}
    #toucan.test_models.user.UserInstance{:id 3, :first-name "Lucky", :last-name "Bird"}}
  (transduce-to-set (db/select-reducible User {:order-by [:id]})))

;; Add up the ids of the users in a transducer
(expect
  6
  (transduce (map :id) + 0 (db/select-reducible User {:order-by [:id]})))

;; Test select-field
(expect
  #{"Lucky" "Rasta" "Cam"}
  (db/select-field :first-name User))

(expect
  #{"Lucky" "Rasta"}
  (db/select-field :first-name User, :id [:> 1]))

;; Test select-ids
(expect
  #{1 3 2}
  (db/select-ids User))

(expect
  #{3 2}
  (db/select-ids User, :id [:not= 1]))

;; Test select-field->field
(expect
  {1 "Cam", 2 "Rasta", 3 "Lucky"}
  (db/select-field->field :id :first-name User))

(expect
  {"Cam" 1, "Rasta" 2, "Lucky" 3}
  (db/select-field->field :first-name :id User))

;; Test select-id->field
(expect
  {1 "Cam", 2 "Rasta", 3 "Lucky"}
  (db/select-id->field :first-name User))

;; Test exists?
(expect
  (db/exists? User, :first-name "Cam"))

(expect
  (db/exists? User, :first-name "Rasta", :last-name "Toucan"))

(expect
  false
  (db/exists? User, :first-name "Kanye", :last-name "Nest"))

;; TODO - Test delete!
