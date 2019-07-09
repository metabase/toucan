(ns toucan.core-test
  (:require [expectations :refer [expect]]
            [toucan.core :as db]))

;; NOCOMMIT
(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))

;;;                                              Shared Aspects & Models
;;; ==================================================================================================================

(db/defaspect A)

(db/defaspect B
  A)

(defmethod db/post-select B
  [_ row]
  (update row :post concat ['B]))

(db/defaspect C
  A
  B)

(db/defmodel MyModel :table
  B
  C)

(defmethod db/pre-select MyModel
  [_ honeysql-form]
  (update honeysql-form :pre concat ['MyModel]))

(defmethod db/post-select MyModel
  [_ row]
  (update row :post concat ['MyModel]))


;;;                                                    Basic Tests
;;; ==================================================================================================================

;; model should just be a namespaced keyword IRL
(expect
  ::MyModel
  MyModel)

(expect
  ::A
  A)

(expect
  [{:result '(query (honeysql->sql {}))}]
  (with-redefs [db/honeysql->sql (fn [_ honeysql]
                                   (list 'honeysql->sql honeysql))
                db/query         (fn [_ x]
                                   [{:result (list 'query x)}])]
    (db/simple-select-honeysql MyModel {})))

(expect
  {:pre ['MyModel]}
  (db/do-pre-select MyModel {}))

(expect
  [{:post '(B MyModel)}]
  (db/do-post-select MyModel [{}]))

(expect
  '[{:result (query (honeysql->sql {:pre [MyModel]}))
     :post    (B MyModel)}]
  (with-redefs [db/honeysql->sql (fn [_ honeysql]
                                   (list 'honeysql->sql honeysql))
                db/query         (fn [_ x]
                                   [{:result (list 'query x)}])]
    (db/select-honeysql MyModel {})))


;;;                                                   Derived Model
;;; ==================================================================================================================


;; TODO - how to derive a model from another model without having to define table again?
(db/defmodel DerivedModel (db/table MyModel)
  MyModel)


;;;                                            Pre-definied aspects: types
;;; ==================================================================================================================

(expect
  {:count 1}
  (db/post-select (db/types {:count (fnil inc 0)}) {}))

(db/defmodel ModelWithInlineType :table
  (db/types {:count (fnil inc 0)}))

(expect
  [{:count 1}]
  (with-redefs [db/honeysql->sql (constantly nil)
                db/query         (constantly [{}])]
    (db/select-honeysql ModelWithInlineType {})))

(defmethod db/type-in ::json [_ v]
  (list 'json v))

(defmethod db/type-out ::json [_ [_ v]]
  (list 'parse-json v))

(db/defmodel ModelWithNamedType :table
  (db/types {:details ::json}))

(expect
  [{:details '(parse-json nil)}]
  (with-redefs [db/honeysql->sql (constantly nil)
                db/query         (constantly [{}])]
    (db/select-honeysql ModelWithNamedType {})))


;;;                                                        Etc
;;; ==================================================================================================================


;; NOCOMMIT
(defn- run-tests []
  (expectations/run-tests [*ns*]))
