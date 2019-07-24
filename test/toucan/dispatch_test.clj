(ns toucan.dispatch-test
  (:require [expectations :refer [expect]]
            [toucan
             [dispatch :as dispatch]
             [instance :as instance]
             [models :as models]
             [operations :as ops]]))

(expect
  nil
  (dispatch/dispatch-value nil))

(expect
 nil
 (dispatch/dispatch-value {}))

(expect
 ::MyModel
 (dispatch/dispatch-value (instance/of ::MyModel {})))

(expect
 ::MyModel
 (dispatch/dispatch-value (with-meta {} {:toucan/dispatch ::MyModel})))

;; string
(expect
 nil
 (dispatch/dispatch-value "str"))

;; symbol
(expect
  nil
  (dispatch/dispatch-value 'symb))

;; vector
(expect
 ::MyModel
 (dispatch/dispatch-value [::MyModel :id :name]))

;; if a sequence has metadata we should dispatch off of that rather than the first arg
(expect
 :sequence-metadata
 (dispatch/dispatch-value (with-meta [::MyModel :id :name] {:toucan/dispatch :sequence-metadata})))

(expect
 :wow
 (dispatch/dispatch-value [(with-meta {} {:toucan/dispatch :wow}) :id :name]))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             combined-method tests                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(models/defmodel A)

(models/defmodel B
  A)

(models/defmodel C
  A
  B)

(models/defmodel D
  C)

(models/defmodel MyModel :table
  B
  C)

(ops/defafter ops/select A [m] (update m :post concat ['A]))
(ops/defafter ops/select B [m] (update m :post concat ['B]))
(ops/defafter ops/select C [m] (update m :post concat ['C]))

(ops/defafter ops/select MyModel [m]
  (update m :post concat ['MyModel]))

(expect
 [A B C MyModel]
 (keys (dispatch/all-advisor-methods [ops/advice :operation/select :advice/after] MyModel)))

(expect
 (every? fn? (vals (dispatch/all-advisor-methods [ops/advice :operation/select :advice/after] MyModel))))

(expect
 {:post '(A B C MyModel)}
 ((dispatch/combined-method [ops/advice :operation/select :advice/after] MyModel) {}))
