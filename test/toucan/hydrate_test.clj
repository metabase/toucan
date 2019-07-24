(ns toucan.hydrate-test
  (:require [expectations :refer [expect]]
            [toucan
             [hydrate :as hydrate]
             [test-models :as m]]))

;; ## TESTS FOR HYDRATION HELPER FNS

;; ### k->k_id
(expect
 :user_id
 (#'hydrate/kw-append :user "_id"))

(expect
 :toucan-id
 (#'hydrate/kw-append :toucan "-id"))

;; ### can-automagic-batched-hydrate?

;; should fail for unknown keys
(expect
 false
 (hydrate/can-hydrate-with-strategy? ::hydrate/automagic-batched [{:a_id 1} {:a_id 2}] :a))

;; should work for known keys if k_id present in every map
(defmethod toucan.hydrate/automagic-hydration-key-model ::user
  [_]
  m/User)

(expect
 (hydrate/can-hydrate-with-strategy? ::hydrate/automagic-batched [{::user_id 1} {::user_id 2}] ::user))

;; should work for both k_id and k-id style keys
(expect
 (hydrate/can-hydrate-with-strategy? ::hydrate/automagic-batched [{::user-id 1} {::user-id 2}] ::user))

;; should fail for known keys if k_id isn't present in every map
(expect
 false
 (hydrate/can-hydrate-with-strategy? ::hydrate/automagic-batched [{::user_id 1} {::user_id 2} {:some-other-key 3}] ::user))

;; ### automagically-batched-hydrate

;; it should correctly hydrate
(defmethod toucan.hydrate/automagic-hydration-key-model ::venue
  [_]
  m/Venue)

(expect
 [{::venue_id 1
   ::venue    {:category :bar, :name "Tempest", :id 1}}
  {::venue-id 2
   ::venue    {:category :bar, :name "Ho's Tavern", :id 2}}]
 (for [result (hydrate/hydrate [{::venue_id 1} {::venue-id 2}] ::venue)]
   (update result ::venue #(dissoc % :updated-at :created-at))))

;; ### valid-hydration-form?
(defn- valid-form? [form]
  (try
    (with-redefs [hydrate/hydrate-key (fn [results k]
                                        (for [result results]
                                          (assoc result k {})))]
      (hydrate/hydrate [{}] form))
    true
    (catch Throwable e
      false)))

(expect true  (valid-form? :k))
(expect false (valid-form? [:k]))
(expect true  (valid-form? [:k :k2]))
(expect false (valid-form? [:k [:k2]]))
(expect false (valid-form? [:k [:k2] :k3]))
(expect true  (valid-form? [:k [:k2 :k3] :k4]))
(expect false (valid-form? [:k [:k2 [:k3]] :k4]))
(expect false (valid-form? 'k))
(expect false (valid-form? [[:k]]))
(expect false (valid-form? [:k [[:k2]]]))
(expect false (valid-form? [:k 'k2]))
(expect false (valid-form? ['k :k2]))
(expect false (valid-form? "k"))


;; ## TESTS FOR HYDRATE INTERNAL FNS

(defmethod hydrate/simple-hydrate [:default ::x]
  [{:keys [id]} _]
  id)

(defmethod hydrate/simple-hydrate [:default ::y]
  [{:keys [id2]} _]
  id2)

(defmethod hydrate/simple-hydrate [:default ::z]
  [{:keys [n]} _]
  (vec (for [i (range n)]
         {:id i})))

;; ### hydrate-key-seq (nested hydration)

;; check with a nested hydration that returns one result
(expect
 [{:f {:id 1, ::x 1}}]
 (#'hydrate/hydrate-key-seq
  [{:f {:id 1}}]
  [:f ::x]))

(expect
 [{:f {:id 1, ::x 1}}
  {:f {:id 2, ::x 2}}]
 (#'hydrate/hydrate-key-seq
  [{:f {:id 1}}
   {:f {:id 2}}]
  [:f ::x]))

;; check with a nested hydration that returns multiple results
(expect
 [{:f [{:id 1, ::x 1}
       {:id 2, ::x 2}
       {:id 3, ::x 3}]}]
 (#'hydrate/hydrate-key-seq
  [{:f [{:id 1}
        {:id 2}
        {:id 3}]}]
  [:f ::x]))

;; ### hydrate-key
(expect
 [{:id 1, ::x 1}
  {:id 2, ::x 2}
  {:id 3, ::x 3}]
 (#'hydrate/hydrate-key
  [{:id 1}
   {:id 2}
   {:id 3}] ::x))

;; ### batched-hydrate

;; ### hydrate - tests for overall functionality

;; make sure we can do basic hydration
(expect
 {:a 1, :id 2, ::x 2}
 (hydrate/hydrate {:a 1, :id 2}
          ::x))

;; specifying "nested" hydration with no "nested" keys should throw an exception and tell you not to do it
(expect
 (str "Invalid hydration form: replace [:b] with :b. Vectors are for nested hydration. "
      "There's no need to use one when you only have a single key.")
 (try (hydrate/hydrate {:a 1, :id 2}
               [:b])
      (catch Throwable e
        (.getMessage e))))

;; check that returning an array works correctly
(expect
 {:n  3
  ::z [{:id 0}
       {:id 1}
       {:id 2}]}
 (hydrate/hydrate {:n 3} ::z))

;; check that nested keys aren't hydrated if we don't ask for it
(expect
 {:d {:id 1}}
 (hydrate/hydrate {:d {:id 1}}
                  :d))

;; check that nested keys can be hydrated if we DO ask for it
(expect
 {:d {:id 1, ::x 1}}
 (hydrate/hydrate {:d {:id 1}}
                  [:d ::x]))

;; check that nested hydration also works if one step returns multiple results
(expect
 {:n 3
  ::z [{:id 0, ::x 0}
      {:id 1, ::x 1}
      {:id 2, ::x 2}]}
 (hydrate/hydrate {:n 3} [::z ::x]))

;; check nested hydration with nested maps
(expect
 [{:f {:id 1, ::x 1}}
  {:f {:id 2, ::x 2}}
  {:f {:id 3, ::x 3}}
  {:f {:id 4, ::x 4}}]
 (hydrate/hydrate [{:f {:id 1}}
                   {:f {:id 2}}
                   {:f {:id 3}}
                   {:f {:id 4}}] [:f ::x]))

;; check that hydration works with top-level nil values
(expect
 [{:id 1, ::x 1}
  {:id 2, ::x 2}
  nil
  {:id 4, ::x 4}]
 (hydrate/hydrate [{:id 1}
                   {:id 2}
                   nil
                   {:id 4}] ::x))

;; check nested hydration with top-level nil values
(expect
 [{:f {:id 1, ::x 1}}
  {:f {:id 2, ::x 2}}
  nil
  {:f {:id 4, ::x 4}}]
 (hydrate/hydrate [{:f {:id 1}}
                   {:f {:id 2}}
                   nil
                   {:f {:id 4}}] [:f ::x]))

;; check that nested hydration w/ nested nil values
(expect
 [{:f {:id 1, ::x 1}}
  {:f {:id 2, ::x 2}}
  {:f nil}
  {:f {:id 4, ::x 4}}]
 (hydrate/hydrate [{:f {:id 1}}
                   {:f {:id 2}}
                   {:f nil}
                   {:f {:id 4}}] [:f ::x]))

(expect
 [{:f {:id 1, ::x 1}}
  {:f {:id 2, ::x 2}}
  {:f {:id nil, ::x nil}}
  {:f {:id 4, ::x 4}}]
 (hydrate/hydrate [{:f {:id 1}}
                   {:f {:id 2}}
                   {:f {:id nil}}
                   {:f {:id 4}}] [:f ::x]))

;; check that it works with some objects missing the key
(expect
 [{:f [{:id 1, ::x 1}
       {:id 2, ::x 2}
       {:g 3, ::x nil}]}
  {:f [{:id 1, ::x 1}]}
  {:f [{:id 4, ::x 4}
       {:g 5, ::x nil}
       {:id 6, ::x 6}]}]
 (hydrate/hydrate [{:f [{:id 1}
                        {:id 2}
                        {:g 3}]}
                   {:f [{:id 1}]}
                   {:f [{:id 4}
                        {:g 5}
                        {:id 6}]}] [:f ::x]))

;; nested-nested hydration
(expect
 [{:f [{:g {:id 1, ::x 1}}
       {:g {:id 2, ::x 2}}
       {:g {:id 3, ::x 3}}]}
  {:f [{:g {:id 4, ::x 4}}
       {:g {:id 5, ::x 5}}]}]
 (hydrate/hydrate
  [{:f [{:g {:id 1}}
        {:g {:id 2}}
        {:g {:id 3}}]}
   {:f [{:g {:id 4}}
        {:g {:id 5}}]}]
  [:f [:g ::x]]))

;; nested + nested-nested hydration
(expect
 [{:f [{:id 1, :g {:id 1, ::x 1}, ::x 1}]}
  {:f [{:id 2, :g {:id 4, ::x 4}, ::x 2}
       {:id 3, :g {:id 5, ::x 5}, ::x 3}]}]
 (hydrate/hydrate [{:f [{:id 1, :g {:id 1}}]}
                   {:f [{:id 2, :g {:id 4}}
                        {:id 3, :g {:id 5}}]}]
                  [:f ::x [:g ::x]]))

;; make sure nested-nested hydration doesn't accidentally return maps where there were none
(expect
 {:f [{:h {:id 1, ::x 1}}
      {}
      {:h {:id 3, ::x 3}}]}
 (hydrate/hydrate {:f [{:h {:id 1}}
                       {}
                       {:h {:id 3}}]}
                  [:f [:h ::x]]))

;; check nested hydration with several keys
(expect
 [{:f [{:id 1, :h {:id 1, :id2 1, ::x 1, ::y 1}, ::x 1}]}
  {:f [{:id 2, :h {:id 4, :id2 2, ::x 4, ::y 2}, ::x 2}
       {:id 3, :h {:id 5, :id2 3, ::x 5, ::y 3}, ::x 3}]}]
 (hydrate/hydrate [{:f [{:id 1, :h {:id 1, :id2 1}}]}
                   {:f [{:id 2, :h {:id 4, :id2 2}}
                        {:id 3, :h {:id 5, :id2 3}}]}]
                  [:f ::x [:h ::x ::y]]))

;; multiple nested-nested hydrations
(expect
 [{:f [{:g {:id 1, ::x 1}, :h {:i {:id2 1, ::y 1}}}]}
  {:f [{:g {:id 2, ::x 2}, :h {:i {:id2 2, ::y 2}}}
       {:g {:id 3, ::x 3}, :h {:i {:id2 3, ::y 3}}}]}]
 (hydrate/hydrate [{:f [{:g {:id 1}
                         :h {:i {:id2 1}}}]}
                   {:f [{:g {:id 2}
                         :h {:i {:id2 2}}}
                        {:g {:id 3}
                         :h {:i {:id2 3}}}]}]
                  [:f [:g ::x] [:h [:i ::y]]]))

;; check that hydration doesn't barf if we ask it to hydrate an object that's not there
(expect
 {:f [:a 100]}
 (hydrate/hydrate {:f [:a 100]} :p))

;;; ## BATCHED HYDRATION TESTS

;; Check that batched hydration doesn't try to hydrate fields that already exist and are not delays
(expect
 {:user_id 1
  :user "OK <3"}
 (hydrate/hydrate {:user_id 1
                   :user "OK <3"}
                  :user))

(defmethod hydrate/batched-hydrate [:default ::is-bird?]
  [objects _]
  (for [object objects]
    (assoc object ::is-bird? true)))

(expect
 [{:type :toucan, ::is-bird? true}
  {:type :pigeon, ::is-bird? true}]
 (hydrate/hydrate [{:type :toucan}
                   {:type :pigeon}]
                  ::is-bird?))

;TODO add test for selecting hydration for where (not= pk :id)
