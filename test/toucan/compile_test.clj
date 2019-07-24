(ns toucan.compile-test
  (:require [expectations :refer [expect]]
            [honeysql.format :as hformat]
            [toucan
             [compile :as compile]
             [db :as db]
             [instance :as instance]
             [models :as models]
             [test-models :as m]]))

(defmethod compile/honeysql-options ::mysql [_]
  {:quoting :mysql})

(defmethod compile/honeysql-options ::ansi [_]
  {:quoting :ansi})

(defmethod compile/honeysql-options ::sqlserver [_]
  {:quoting :sqlserver})

;; Test `quoting-style` and `quote-fn`

;; `:ansi` is the default style
(expect
 :ansi
 (compile/quoting-style))

(expect
 :ansi
 (compile/quoting-style nil))

(expect
 :mysql
 (compile/quoting-style ::mysql))

(expect
 (get @#'hformat/quote-fns :mysql)
 (compile/quote-fn ::mysql))

(expect
 "\"toucan\""
 ((compile/quote-fn) "toucan"))

(expect
 "`toucan`"
 ((compile/quote-fn ::myql) "toucan"))

(expect
 "\"toucan\""
 ((compile/quote-fn ::ansi) "toucan"))

(expect
 "[toucan]"
 ((compile/quote-fn ::sqlserver) "toucan"))

;; Test allowing dashed field names

(models/defmodel AutoConvertAddress)

(derive AutoConvertAddress m/Address)

(defmethod compile/automatically-convert-dashes-and-underscores? AutoConvertAddress
  [_]
  true)

;; default value = false
(expect
 false
 (compile/automatically-convert-dashes-and-underscores? nil))

(expect
 false
 (compile/automatically-convert-dashes-and-underscores? m/Address))

(expect
 (compile/automatically-convert-dashes-and-underscores? AutoConvertAddress))

;; TODO - don't think these belong here
(expect
  {:street_name "1 Toucan Drive"}
  (db/select-one [m/Address :street_name]))

(expect
 {:street-name "1 Toucan Drive"}
 (db/select-one [AutoConvertAddress :street-name]))

(expect
 "1 Toucan Drive"
 (db/select-one-field :street-name AutoConvertAddress))

;; Test qualify
(expect
  :users.first-name
  (compile/qualify m/User :first-name))

(expect
  :users.first-name
  (compile/qualify m/User "first-name"))

;; test qualified?
(expect true  (compile/qualified? :users.first-name))
(expect true  (compile/qualified? "users.first-name"))
(expect false (compile/qualified? :first-name))
(expect false (compile/qualified? "first-name"))

;; `compile-select`

;; with one arg (instance)
(expect
 {:select [:*], :from [:address], :where [:= :id 1]}
 (compile/compile-select (instance/of AutoConvertAddress {:id 1})))

;; one arg wrapped in fields
(expect
 {:select [:a :b], :from [:address], :where [:= :id 1]}
 (compile/compile-select [(instance/of AutoConvertAddress {:id 1}) :a :b]))

;; TODO 2-arg (pk value)

;; TODO 2-arg (HoneySQL form)

;; TODO 2-arg (pk value) + wrapped model

;; TODO 2-arg (HoneySQL form) + wrapped model

;; TODO 3+args: key-value args

;; TODO 3+args: HoneySQL maps args

;; TODO 3+args with wrapped model
