(ns toucan.instance-test
  (:require [expectations :refer [expect]]
            [toucan.instance :as instance]))

(expect
  (= {} (instance/of ::MyModel)))

(expect
  (= {} (instance/of ::MyModel {})))

(expect
  (= {:a 1} (instance/of ::MyModel {:a 1})))

(expect
  nil
  (instance/model {}))

(expect
  ::MyModel
  (instance/model (instance/of ::MyModel)))

(expect
  ::MyModel
  (instance/model (instance/of ::MyModel {})))

(expect
  nil
  (instance/model {}))

(expect
  nil
  (instance/model nil))

(expect
  {:original? false}
  (assoc (instance/of ::MyModel {:original? true}) :original? false))

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
