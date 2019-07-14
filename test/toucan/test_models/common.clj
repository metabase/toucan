(ns toucan.test-models.common
  (:require [toucan.models.options :as options]
            [toucan.dispatch :as dispatch]))

(defmulti definition
  {:arglists '([model])}
  dispatch/dispatch-value)
