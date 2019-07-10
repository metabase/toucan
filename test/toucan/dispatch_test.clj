(ns toucan.dispatch-test
  (:require [expectations :refer [expect]]
            [flatland.ordered.map :as ordered-map]
            [toucan
             [core :as db]
             [dispatch :as dispatch]
             [instance :as instance]]))

;; NOCOMMIT
(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))

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

(db/defaspect A)

(db/defaspect B
  A)

(db/defaspect C
  A
  B)

(db/defaspect D
  C)

(db/defmodel MyModel :table
  B
  C)

(defmethod db/post-select A [_ m] (update m :post concat ['A]))
(defmethod db/post-select B [_ m] (update m :post concat ['B]))
(defmethod db/post-select C [_ m] (update m :post concat ['C]))

(defmethod db/post-select MyModel [_ m] (update m :post concat ['MyModel]))

(expect
  (ordered-map/ordered-map
   A       (get-method db/post-select A)
   B       (get-method db/post-select B)
   C       (get-method db/post-select C)
   MyModel (get-method db/post-select MyModel))
  (dispatch/all-aspect-methods db/post-select MyModel))

(expect
  {:post '(A B C MyModel)}
  ((dispatch/combined-method db/post-select MyModel) {}))
