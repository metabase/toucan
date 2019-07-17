(ns toucan.db-test
  (:require [expectations :refer [expect]]
            [toucan
             [connection :as connection]
             [db :as db]
             [debug :as debug]
             [operations :as ops]
             [test-models :as m]
             [test-setup :as test]]))

;; Test replace-underscores

;; Test transaction
(expect
 0
 ;; attempt to insert! two of the same Venues. Since the second has a duplicate name,
 ;; the whole transaction should fail, and neither should get inserted.
 (test/with-clean-db
   (try
     (connection/transaction
       (db/insert! m/Venue :name "Cam's Toucannery", :category "Pet Store")
       (db/insert! m/Venue :name "Cam's Toucannery", :category "Pet Store"))
     (catch Throwable _))
   (db/count m/Venue :name "Cam's Toucannery")))

;; TODO - Test DB logging (how?)

;; Test with-call-counting
(expect
 2
 (debug/with-call-counting [call-count]
   (db/select-one m/User)
   (db/select-one m/User)
   (call-count)))

;; Test debug-count-calls
(expect
 "DB Calls: 2\n"
 (with-out-str (debug/debug-count-calls
                 (db/select-one m/User)
                 (db/select-one m/User))))

;; TODO - Test format-sql

;; TODO - Test debug-print-queries

;; Test query
(expect
 [{:id 1, :first-name "Cam", :last-name "Saul"}]
 (ops/query {:select   [:*]
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
 (transduce-to-set (ops/reducible-query {:select   [:*]
                                         :from     [:users]
                                         :order-by [:id]
                                         :limit    1})))



;; Simple `select` tests
(expect
 [{:id 1, :first-name "Cam", :last-name "Saul"}]
 (db/select m/User {:where [:= :id 1]}))

(expect
 [{:id 3, :first-name "Lucky", :last-name "Bird"}]
 (db/select m/User {:where [:and [:not= :id 1] [:not= :id 2]]}))

;; Test simple-select-reducible
(expect
 #{{:id 1, :first-name "Cam", :last-name "Saul"}}
 (transduce-to-set (ops/select-reducible m/User {:where [:= :id 1]})))

;; Test select-one
(expect
 {:id 1, :first-name "Cam", :last-name "Saul"}
 (db/select-one m/User {:where [:= :first-name "Cam"]}))

;; TODO - Test execute!

;; Test update!
(expect
 {:id 1, :first-name "Cam", :last-name "Era"}
 (test/with-clean-db
   (db/update! m/User 1 :last-name "Era")
   (db/select-one m/User :id 1)))

(expect
 {:number "012345678", :country_code "AU"}
 (test/with-clean-db
   (let [id "012345678"]
     (ops/ignore-advice
       (db/insert! m/PhoneNumber {:number id, :country_code "US"}))
     (db/update! m/PhoneNumber id :country_code "AU")
     (db/select-one m/PhoneNumber :number id))))

;; Test update-where!
(expect
 [{:id 1, :first-name "Cam", :last-name "Saul"}
  {:id 2, :first-name "Cam", :last-name "Toucan"}
  {:id 3, :first-name "Cam", :last-name "Bird"}]
 (test/with-clean-db
   (db/update-where! m/User {:first-name [:not= "Cam"]}
     :first-name "Cam")
   (db/select m/User {:order-by [:id]})))

(expect
 [{:id 1, :first-name "Not Cam", :last-name "Saul"}
  {:id 2, :first-name "Rasta", :last-name "Toucan"}
  {:id 3, :first-name "Lucky", :last-name "Bird"}]
 (test/with-clean-db
   (db/update-where! m/User {:first-name "Cam"}
     :first-name "Not Cam")
   (db/select m/User {:order-by [:id]})))

;; Test update-non-nil-keys!
(expect
 {:id 2, :first-name "Rasta", :last-name "Can"}
 (test/with-clean-db
   (db/update-non-nil-keys! m/User 2
     :first-name nil
     :last-name "Can")
   (m/User 2)))

(expect
 {:id 2, :first-name "Rasta", :last-name "Can"}
 (test/with-clean-db
   (db/update-non-nil-keys! m/User 2
     {:first-name nil
      :last-name  "Can"})
   (m/User 2)))

;; TODO - Test simple-delete!

;;; Test simple-insert-many!

;; It must return the inserted ids
(expect
 [5]
 (test/with-clean-db
   (db/insert! m/Category [{:name "seafood" :parent-category-id 100}])))

;; it must not fail when using SQL function calls.
(expect
 [4 5]
 (test/with-clean-db
   (db/insert! m/User [{:first-name "Grass" :last-name #sql/call [:upper "Hopper"]}
                       {:first-name "Ko" :last-name "Libri"}])))

;;; Test insert-many!

;; It must return the inserted ids, it must not fail when using SQL function calls.
(expect
 [4 5]
 (test/with-clean-db
   (db/insert! m/User [{:first-name "Grass" :last-name #sql/call [:upper "Hopper"]}
                          {:first-name "Ko" :last-name "Libri"}])))

;; It must call pre-insert hooks
(expect
 AssertionError ; triggered by pre-insert hook
 (test/with-clean-db
   (db/insert! m/Category [{:name "seafood" :parent-category-id 100}])))

;; TODO - Test simple-insert!

;;; Test insert!

;; It must return the inserted model
(expect
 {:id 4, :first-name "Trash", :last-name "Bird"}
 (test/with-clean-db
   (db/insert! m/User {:first-name "Trash", :last-name "Bird"})))

(expect
  {:number "012345678", :country_code "AU"}
  (test/with-clean-db
    (db/insert! m/PhoneNumber {:number "012345678", :country_code "AU"})))

;; The returned data must match what's been inserted in the table
(expect
 {:id 4, :first-name "Grass", :last-name "HOPPER"}
 (test/with-clean-db
   (db/insert! m/User {:first-name "Grass" :last-name #sql/call [:upper "Hopper"]})))

;; Test select-one
(expect
  {:id 1, :first-name "Cam", :last-name "Saul"}
  (db/select-one m/User, :first-name "Cam"))

(expect
  {:id 3, :first-name "Lucky", :last-name "Bird"}
  (db/select-one m/User {:order-by [[:id :desc]]}))

(expect
  {:first-name "Lucky", :last-name "Bird"}
  (db/select-one [m/User :first-name :last-name] {:order-by [[:id :desc]]}))

;; Test select-one-field
(expect
  "Cam"
  (db/select-one-field :first-name m/User, :id 1))

(expect
  1
  (db/select-one-field :id m/User, :first-name "Cam"))

;; Test select-one-id
(expect
  1
  (db/select-one-id m/User, :first-name "Cam"))

;; Test count
(expect
  3
  (db/count m/User))

(expect
  1
  (db/count m/User, :first-name "Cam"))

(expect
  2
  (db/count m/User, :first-name [:not= "Cam"]))

;; Test select
(expect
  [{:id 1, :first-name "Cam",   :last-name "Saul"}
   {:id 2, :first-name "Rasta", :last-name "Toucan"}
   {:id 3, :first-name "Lucky", :last-name "Bird"}]
  (db/select m/User {:order-by [:id]}))

(expect
  [{:id 2, :first-name "Rasta", :last-name "Toucan"}
   {:id 3, :first-name "Lucky", :last-name "Bird"}]
  (db/select m/User
    :first-name [:not= "Cam"]
    {:order-by [:id]}))

(expect
  [{:first-name "Cam",   :last-name "Saul"}
   {:first-name "Rasta", :last-name "Toucan"}
   {:first-name "Lucky", :last-name "Bird"}]
  (db/select [m/User :first-name :last-name] {:order-by [:id]}))

;; Check that `select` works as we'd expect with where clauses with more than two arguments, for example BETWEEN
(expect
  [{:first-name "Cam",   :last-name "Saul"}
   {:first-name "Rasta", :last-name "Toucan"}]
  (db/select [m/User :first-name :last-name] :id [:between 1 2] {:order-by [:id]}))

;; Test select-reducible
(expect
  #{{:id 1, :first-name "Cam",   :last-name "Saul"}
    {:id 2, :first-name "Rasta", :last-name "Toucan"}
    {:id 3, :first-name "Lucky", :last-name "Bird"}}
  (transduce-to-set (db/select-reducible m/User {:order-by [:id]})))

;; Add up the ids of the users in a transducer
(expect
  6
  (transduce (map :id) + 0 (db/select-reducible m/User {:order-by [:id]})))

;; Test select-field
(expect
  #{"Lucky" "Rasta" "Cam"}
  (db/select-field-set :first-name m/User))

(expect
  #{"Lucky" "Rasta"}
  (db/select-field-set :first-name m/User, :id [:> 1]))

;; Test select-ids
(expect
  #{1 3 2}
  (db/select-id-set m/User))

(expect
  #{3 2}
  (db/select-id-set m/User, :id [:not= 1]))

;; Test select-field->field
(expect
  {1 "Cam", 2 "Rasta", 3 "Lucky"}
  (db/select-field->field :id :first-name m/User))

(expect
  {"Cam" 1, "Rasta" 2, "Lucky" 3}
  (db/select-field->field :first-name :id m/User))

;; Test select-id->field
(expect
  {1 "Cam", 2 "Rasta", 3 "Lucky"}
  (db/select-id->field :first-name m/User))

;; Test exists?
(expect
  (db/exists? m/User, :first-name "Cam"))

(expect
  (db/exists? m/User, :first-name "Rasta", :last-name "Toucan"))

(expect
  false
  (db/exists? m/User, :first-name "Kanye", :last-name "Nest"))

;; TODO - Test delete!
