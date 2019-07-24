(ns toucan.instance-test
  (:require [expectations :refer [expect]]
            [toucan
             [dispatch :as dispatch]
             [instance :as instance]]))

(expect
  (= {} (instance/of ::MyModel)))

(expect
  (= {} (instance/of ::MyModel {})))

(expect
  (= {:a 1} (instance/of ::MyModel {:a 1})))

(expect
  ::MyModel
  (dispatch/dispatch-value (instance/of ::MyModel)))

(expect
  ::MyModel
  (dispatch/dispatch-value (instance/of ::MyModel {})))

(expect
  {:original? false}
  (assoc (instance/of ::MyModel {:original? true}) :original? false))

(expect
 {:original? true}
 (.orig (instance/of ::MyModel {:original? true})))

(expect
 {:original? true}
 (.m (instance/of ::MyModel {:original? true})))

(expect
 {:original? true}
 (.orig ^toucan.instance.ToucanInstance (assoc (instance/of ::MyModel {:original? true}) :original? false)))

(expect
 {:original? false}
 (.m ^toucan.instance.ToucanInstance (assoc (instance/of ::MyModel {:original? true}) :original? false)))

(expect
 {:original? true}
 (instance/original (assoc (instance/of ::MyModel {:original? true}) :original? false)))

(expect
 {}
 (dissoc (instance/of ::MyModel {:original? true}) :original?))

(expect
  {:original? true}
  (instance/original (dissoc (instance/of ::MyModel {:original? true}) :original?)))

(expect
  nil
  (instance/original {}))

(expect
  nil
  (instance/original nil))
