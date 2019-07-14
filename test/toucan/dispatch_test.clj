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
  ::MyAspect
  (dispatch/dispatch-value {:toucan/model ::MyAspect}))

;; string
(expect
  :toucan.dispatch-test/MyAspect
  (dispatch/dispatch-value "toucan.dispatch-test/MyAspect"))

;; symbol
(expect
  :toucan.dispatch-test/MyAspect
  (dispatch/dispatch-value 'toucan.dispatch-test/MyAspect))

;; vector
(expect
 ::MyModel
 (dispatch/dispatch-value [::MyModel :id :name]))

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
