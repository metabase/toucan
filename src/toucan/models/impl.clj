(ns toucan.models.impl
  (:require [toucan
             [dispatch :as dispatch]
             [instance :as instance]]))

;; NOCOMMIT
(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))



(defmethod init-aspect :table
  [model _ [table-kw]]
  (.addMethod ^clojure.lang.MultiFn instance/table (dispatch/dispatch-value model) (constantly table-kw))
  nil)

;; TODO - parent option. `isa` derivation
