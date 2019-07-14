(ns toucan.db-test
  (:require [expectations :refer [expect]]
            [toucan
             [db :as db]
             [test-setup :as test]]
            [toucan.test-models :as mk]
            [toucan.connection :as connection]))



;; Test replace-underscores

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

(expect
  #toucan.test_models.phone_number.PhoneNumberInstance{:number "012345678", :country_code "AU"}
  (test/with-clean-db
    (let [id "012345678"]
      (db/simple-insert! PhoneNumber {:number id, :country_code "US"})
      (db/update! PhoneNumber id :country_code "AU")
      (db/select-one PhoneNumber :number id))))

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

(expect
  #toucan.test_models.phone_number.PhoneNumberInstance{:number "012345678", :country_code "AU"}
  (test/with-clean-db
    (db/insert! PhoneNumber {:number "012345678", :country_code "AU"})))

;; The returned data must match what's been inserted in the table
(expect
 #toucan.test_models.user.UserInstance{:id 4, :first-name "Grass", :last-name "HOPPER"}
 (test/with-clean-db
   (db/insert! User {:first-name "Grass" :last-name #sql/call [:upper "Hopper"]})))

;; get-inserted-id shouldn't fail if nothing is returned for some reason
(expect
 nil
 (db/get-inserted-id :id nil))

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
