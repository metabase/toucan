(ns toucan.dispatch-test
  (:require [expectations :refer [expect]]
            [flatland.ordered.map :as ordered-map]
            [toucan
             [dispatch :as dispatch]
             [instance :as instance]
             [models :as models]]))

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

(defmethod models/post-select A [_ m] (update m :post concat ['A]))
(defmethod models/post-select B [_ m] (update m :post concat ['B]))
(defmethod models/post-select C [_ m] (update m :post concat ['C]))

(defmethod models/post-select MyModel [_ m] (update m :post concat ['MyModel]))

(expect
  (ordered-map/ordered-map
   A       (get-method models/post-select A)
   B       (get-method models/post-select B)
   C       (get-method models/post-select C)
   MyModel (get-method models/post-select MyModel))
  (dispatch/all-aspect-methods models/post-select MyModel))

(expect
  {:post '(A B C MyModel)}
  ((dispatch/combined-method models/post-select MyModel) {}))
