(ns toucan.util-test
  (:require [expectations :refer [expect]]
            [toucan.util :as u]))

(expect
 :2-cans
 (u/replace-underscores :2_cans))

;; shouldn't do anything to keywords with no underscores
(expect
 :2-cans
 (u/replace-underscores :2-cans))

;; should work with strings as well
(expect
 :2-cans
 (u/replace-underscores "2_cans"))

;; make sure it respects namespaced keywords or keywords with slashes in them
(expect
 :bird-types/two-cans
 (u/replace-underscores :bird-types/two_cans))

;; don't barf if there's a nil input
(expect
 nil
 (u/replace-underscores nil))

;; shouldn't do anything for numbers!
(expect
 2
 (u/replace-underscores 2))

;; Test transform-keys
(expect
 {:2-cans true}
 (u/transform-keys u/replace-underscores {:2_cans true}))

;; make sure it works recursively and inside arrays
(expect
 [{:2-cans {:2-cans true}}]
 (u/transform-keys u/replace-underscores [{:2_cans {:2_cans true}}]))

(expect
 {:a 1, :b 2, :c 3}
 (u/varargs->map '(:a 1 :b 2 :c 3)))

(expect
 {:k :v, :a 1, :b 2, :c 3}
 (u/varargs->map :k :v '(:a 1 :b 2 :c 3)))
