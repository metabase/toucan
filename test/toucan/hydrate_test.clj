(ns toucan.hydrate-test
  (:require [expectations :refer [expect]]
            [toucan.hydrate :refer [hydrate] :as hydrate]
            [toucan.test-models
             [user :refer [User]]
             [venue :refer [Venue]]]
            toucan.test-setup))

(defn- ^:hydrate x [{:keys [id]}]
  id)

(defn- ^:hydrate y [{:keys [id2]}]
  id2)

(defn- ^:hydrate z [{:keys [n]}]
  (vec (for [i (range n)]
         {:id i})))

;; ## TESTS FOR HYDRATION HELPER FNS

;; ### k->k_id
(expect
  :user_id
  (#'hydrate/kw-append :user "_id"))

(expect
  :toucan-id
  (#'hydrate/kw-append :toucan "-id"))

;; ### can-automagically-batched-hydrate?

;; should fail for unknown keys
(expect
  false
  (#'hydrate/can-automagically-batched-hydrate? [{:a_id 1} {:a_id 2}] :a))

;; should work for known keys if k_id present in every map
(expect
  (with-redefs [toucan.hydrate/automagic-batched-hydration-key->model (constantly #{:user User})]
    (#'hydrate/can-automagically-batched-hydrate? [{:user_id 1} {:user_id 2}] :user)))

;; should work for both k_id and k-id style keys
(expect
  (with-redefs [toucan.hydrate/automagic-batched-hydration-key->model (constantly #{:user User})]
    (#'hydrate/can-automagically-batched-hydrate? [{:user_id 1} {:user-id 2}] :user)))

;; should fail for known keys if k_id isn't present in every map
(expect
  false
  (#'hydrate/can-automagically-batched-hydrate? [{:user_id 1} {:user_id 2} {:x 3}] :user))

;; ### automagically-batched-hydrate

;; it should correctly hydrate
(expect
 '({:venue_id 1
    :venue    #toucan.test_models.venue.VenueInstance{:category :bar, :name "Tempest", :id 1}}
   {:venue-id 2
    :venue    #toucan.test_models.venue.VenueInstance{:category :bar, :name "Ho's Tavern", :id 2}})
  (with-redefs [toucan.hydrate/automagic-batched-hydration-key->model (constantly {:venue Venue})]
    (#'hydrate/automagically-batched-hydrate [{:venue_id 1} {:venue-id 2}] :venue)))

;; ### valid-hydration-form?
(expect true (#'hydrate/valid-hydration-form? :k))
(expect true (#'hydrate/valid-hydration-form? [:k]))
(expect true (#'hydrate/valid-hydration-form? [:k :k2]))
(expect true (#'hydrate/valid-hydration-form? [:k [:k2]]))
(expect true (#'hydrate/valid-hydration-form? [:k [:k2] :k3]))
(expect true (#'hydrate/valid-hydration-form? [:k [:k2 :k3] :k4]))
(expect true (#'hydrate/valid-hydration-form? [:k [:k2 [:k3]] :k4]))
(expect false (#'hydrate/valid-hydration-form? 'k))
(expect false (#'hydrate/valid-hydration-form? [[:k]]))
(expect false (#'hydrate/valid-hydration-form? [:k [[:k2]]]))
(expect false (#'hydrate/valid-hydration-form? [:k 'k2]))
(expect false (#'hydrate/valid-hydration-form? ['k :k2]))
(expect false (#'hydrate/valid-hydration-form? "k"))

;; Can we *refresh* Hydration keys?

;; Here, we have a var named `awesome`. Note that you cannot hydrate with it
(def ^:private awesome (constantly 100))

;; and suddenly marking it 'hydrate' shouldn't change that fact, because hydration keys are cached
(expect
  {}
  (try
    ;; first, make sure the hydration keys get cached by calling the function that caches them
    ;; hydrate/hydration-key->f
    (#'hydrate/hydration-key->f)
    ;; now alter the metadata for `awesome` so it becomes hydrateable
    (alter-meta! #'awesome assoc :hydrate true)
    ;; try hydrating with it -- shouldn't work because cache hasn't been updated
    (hydrate {} :awesome)
    ;; finally, at the end of everything, restore `awesome` to its original state so it doesn't affect future tests.
    (finally
      (alter-meta! #'awesome assoc :hydrate false)
      ;; ...and manually remove `awesome` as a hydration key from the cache if it somehow got in there
      (swap! @#'hydrate/hydration-key->f* dissoc :awesome))))

;; ok, now try the same thing as above, but..
(expect
  {:awesome 100}
  (try
    (#'hydrate/hydration-key->f)
    (alter-meta! #'awesome assoc :hydrate true)
    ;; ...this time call our new function to flush the hydration keys caches (!)
    (hydrate/flush-hydration-key-caches!)
    ;; now hydration should work with the new key
    (hydrate {} :awesome)
    ;; finally, restore everthing to the way it was :D
    (finally
      (alter-meta! #'awesome assoc :hydrate false)
      (swap! @#'hydrate/hydration-key->f* dissoc :awesome))))




;; ### counts-of

(expect
  [:atom :atom]
  (#'hydrate/counts-of
    [{:f {:id 1}}
     {:f {:id 2}}]
    :f))

(expect
  [2 2]
  (#'hydrate/counts-of
    [{:f [{:id 1} {:id 2}]}
     {:f [{:id 3} {:id 4}]}]
    :f))

(expect
  [3 2]
  (#'hydrate/counts-of
    [{:f [{:g {:i {:id 1}}}
          {:g {:i {:id 2}}}
          {:g {:i {:id 3}}}]}
     {:f [{:g {:i {:id 4}}}
          {:g {:i {:id 5}}}]}]
    :f))

(expect
  [2 :atom :nil]
  (#'hydrate/counts-of
    [{:f [:a :b]}
     {:f {:c 1}}
     {:f nil}]
    :f))

(expect
  [:atom
   :atom
   :nil
   :atom]
  (#'hydrate/counts-of
    [{:f {:id 1}}
     {:f {:id 2}}
     {:f nil}
     {:f {:id 4}}]
    :f))

(expect
  [:atom nil :nil :atom]
  (#'hydrate/counts-of
    [{:h {:i {:id 1}}}
     {}
     {:h nil}
     {:h {:i {:id 3}}}]
    :h))

;; ### counts-flatten
(expect
  [{:g {:i {:id 1}}}
   {:g {:i {:id 2}}}
   {:g {:i {:id 3}}}
   {:g {:i {:id 4}}}
   {:g {:i {:id 5}}}]
  (#'hydrate/counts-flatten
    [{:f [{:g {:i {:id 1}}}
          {:g {:i {:id 2}}}
          {:g {:i {:id 3}}}]}
     {:f [{:g {:i {:id 4}}}
          {:g {:i {:id 5}}}]}]
    :f))

(expect
  [1 2 nil]
  (#'hydrate/counts-flatten
    [{:f 1}
     {:f 2}
     nil]
    :f))

(expect
  [{:g 1} {:g 2} nil {:g 4}]
  (#'hydrate/counts-flatten
    [{:f {:g 1}}
     {:f {:g 2}}
     nil
     {:f {:g 4}}]
    :f))

;; ### #'hydrate/counts-unflatten
(expect
  [{:f [{:g {:i {:id 1}}}
        {:g {:i {:id 2}}}
        {:g {:i {:id 3}}}]}
   {:f [{:g {:i {:id 4}}}
        {:g {:i {:id 5}}}]}]
  (#'hydrate/counts-unflatten
    [{:g {:i {:id 1}}}
     {:g {:i {:id 2}}}
     {:g {:i {:id 3}}}
     {:g {:i {:id 4}}}
     {:g {:i {:id 5}}}] :f [3 2]))

(expect
  [{:f {:g 1}}
   {:f {:g 2}}
   nil
   {:f {:g 4}}]
  (#'hydrate/counts-unflatten
    [{:g 1} {:g 2} nil {:g 4}]
    :f
    [:atom :atom nil :atom]))

;; ### #'hydrate/counts-apply

(expect
  [{:f {:id 1}}
   {:f {:id 2}}]
  (#'hydrate/counts-apply
    [{:f {:id 1}}
     {:f {:id 2}}]
    :f
    identity))

(expect
  [{:f [{:id 1} {:id 2}]}
   {:f [{:id 3} {:id 4}]}]
  (#'hydrate/counts-apply
    [{:f [{:id 1} {:id 2}]}
     {:f [{:id 3} {:id 4}]}]
    :f
    identity))

(expect
  [{:f [{:g {:i {:id 1}}}
        {:g {:i {:id 2}}}
        {:g {:i {:id 3}}}]}
   {:f [{:g {:i {:id 4}}}
        {:g {:i {:id 5}}}]}]
  (#'hydrate/counts-apply
    [{:f [{:g {:i {:id 1}}}
          {:g {:i {:id 2}}}
          {:g {:i {:id 3}}}]}
     {:f [{:g {:i {:id 4}}}
          {:g {:i {:id 5}}}]}]
    :f
    identity))

(expect
  [{:f {:g 1}}
   {:f {:g 2}}
   {:f nil}
   nil
   {:f {:g 3}}]
  (#'hydrate/counts-apply
    [{:f {:g 1}}
     {:f {:g 2}}
     {:f nil}
     nil
     {:f {:g 3}}]
    :f
    identity))

;; ## TESTS FOR HYDRATE INTERNAL FNS

;; ### hydrate-vector (nested hydration)
;; check with a nested hydration that returns one result
(expect
  [{:f {:id 1, :x 1}}]
  (#'hydrate/hydrate-vector
    [{:f {:id 1}}]
    [:f :x]))

(expect
  [{:f {:id 1, :x 1}}
   {:f {:id 2, :x 2}}]
  (#'hydrate/hydrate-vector
    [{:f {:id 1}}
     {:f {:id 2}}]
    [:f :x]))

;; check with a nested hydration that returns multiple results
(expect
  [{:f [{:id 1, :x 1}
        {:id 2, :x 2}
        {:id 3, :x 3}]}]
  (#'hydrate/hydrate-vector
    [{:f [{:id 1}
          {:id 2}
          {:id 3}]}]
    [:f :x]))

;; ### hydrate-kw
(expect
  [{:id 1, :x 1}
   {:id 2, :x 2}
   {:id 3, :x 3}]
  (#'hydrate/hydrate-kw
    [{:id 1}
     {:id 2}
     {:id 3}] :x))

;; ### batched-hydrate

;; ### hydrate - tests for overall functionality

;; make sure we can do basic hydration
(expect
  {:a 1, :id 2, :x 2}
  (hydrate {:a 1, :id 2}
           :x))

;; specifying "nested" hydration with no "nested" keys should throw an exception and tell you not to do it
(expect
 (str "Assert failed: Replace '[:b]' with ':b'. Vectors are for nested hydration. "
      "There's no need to use one when you only have a single key.\n(> (count vect) 1)")
  (try (hydrate {:a 1, :id 2}
                [:b])
       (catch Throwable e
         (.getMessage e))))

;; check that returning an array works correctly
(expect {:n 3
         :z [{:id 0}
             {:id 1}
             {:id 2}]}
        (hydrate {:n 3} :z))

;; check that nested keys aren't hydrated if we don't ask for it
(expect {:d {:id 1}}
  (hydrate {:d {:id 1}}
           :d))

;; check that nested keys can be hydrated if we DO ask for it
(expect {:d {:id 1, :x 1}}
  (hydrate {:d {:id 1}}
           [:d :x]))

;; check that nested hydration also works if one step returns multiple results
(expect {:n 3
         :z [{:id 0, :x 0}
             {:id 1, :x 1}
             {:id 2, :x 2}]}
  (hydrate {:n 3} [:z :x]))

;; check nested hydration with nested maps
(expect
  [{:f {:id 1, :x 1}}
   {:f {:id 2, :x 2}}
   {:f {:id 3, :x 3}}
   {:f {:id 4, :x 4}}]
  (hydrate [{:f {:id 1}}
            {:f {:id 2}}
            {:f {:id 3}}
            {:f {:id 4}}] [:f :x]))

;; check with a nasty mix of maps and seqs
(expect
  [{:f [{:id 1, :x 1} {:id 2, :x 2} {:id 3, :x 3}]}
   {:f {:id 1, :x 1}}
   {:f [{:id 4, :x 4} {:id 5, :x 5} {:id 6, :x 6}]}]
  (hydrate [{:f [{:id 1}
                 {:id 2}
                 {:id 3}]}
            {:f {:id 1}}
            {:f [{:id 4}
                 {:id 5}
                 {:id 6}]}] [:f :x]))

;; check that hydration works with top-level nil values
(expect
  [{:id 1, :x 1}
   {:id 2, :x 2}
   nil
   {:id 4, :x 4}]
  (hydrate [{:id 1}
            {:id 2}
            nil
            {:id 4}] :x))

;; check nested hydration with top-level nil values
(expect
  [{:f {:id 1, :x 1}}
   {:f {:id 2, :x 2}}
   nil
   {:f {:id 4, :x 4}}]
  (hydrate [{:f {:id 1}}
            {:f {:id 2}}
            nil
            {:f {:id 4}}] [:f :x]))

;; check that nested hydration w/ nested nil values
(expect
  [{:f {:id 1, :x 1}}
   {:f {:id 2, :x 2}}
   {:f nil}
   {:f {:id 4, :x 4}}]
  (hydrate [{:f {:id 1}}
            {:f {:id 2}}
            {:f nil}
            {:f {:id 4}}] [:f :x]))

(expect
  [{:f {:id 1, :x 1}}
   {:f {:id 2, :x 2}}
   {:f {:id nil, :x nil}}
   {:f {:id 4, :x 4}}]
  (hydrate [{:f {:id 1}}
            {:f {:id 2}}
            {:f {:id nil}}
            {:f {:id 4}}] [:f :x]))

;; check that it works with some objects missing the key
(expect
  [{:f [{:id 1, :x 1}
        {:id 2, :x 2}
        {:g 3, :x nil}]}
   {:f {:id 1, :x 1}}
   {:f [{:id 4, :x 4}
        {:g 5, :x nil}
        {:id 6, :x 6}]}]
  (hydrate [{:f [{:id 1}
                 {:id 2}
                 {:g 3}]}
            {:f {:id 1}}
            {:f [{:id 4}
                 {:g 5}
                 {:id 6}]}] [:f :x]))

;; check that we can handle wonky results: :f is [sequence, map sequence] respectively
(expect
  [{:f [{:id 1, :id2 10, :x 1, :y 10}
        {:id 2, :x 2, :y nil}
        {:id 3, :id2 30, :x 3, :y 30}]}
   {:f {:id 1, :id2 10, :x 1, :y 10}}
   {:f [{:id 4, :x 4, :y nil}
        {:id 5, :id2 50, :x 5, :y 50}
        {:id 6, :x 6, :y nil}]}]
  (hydrate [{:f [{:id 1, :id2 10}
                 {:id 2}
                 {:id 3, :id2 30}]}
            {:f {:id 1, :id2 10}}
            {:f [{:id 4}
                 {:id 5, :id2 50}
                 {:id 6}]}] [:f :x :y]))

;; nested-nested hydration
(expect
  [{:f [{:g {:id 1, :x 1}}
        {:g {:id 2, :x 2}}
        {:g {:id 3, :x 3}}]}
   {:f [{:g {:id 4, :x 4}}
        {:g {:id 5, :x 5}}]}]
  (hydrate [{:f [{:g {:id 1}}
                 {:g {:id 2}}
                 {:g {:id 3}}]}
            {:f [{:g {:id 4}}
                 {:g {:id 5}}]}]
           [:f [:g :x]]))

;; nested + nested-nested hydration
(expect
  [{:f [{:id 1, :g {:id 1, :x 1}, :x 1}]}
   {:f [{:id 2, :g {:id 4, :x 4}, :x 2}
        {:id 3, :g {:id 5, :x 5}, :x 3}]}]
  (hydrate [{:f [{:id 1, :g {:id 1}}]}
            {:f [{:id 2, :g {:id 4}}
                 {:id 3, :g {:id 5}}]}]
           [:f :x [:g :x]]))

;; make sure nested-nested hydration doesn't accidentally return maps where there were none
(expect
  {:f [{:h {:id 1, :x 1}}
       {}
       {:h {:id 3, :x 3}}]}
  (hydrate {:f [{:h {:id 1}}
                {}
                {:h {:id 3}}]}
           [:f [:h :x]]))

;; check nested hydration with several keys
(expect
  [{:f [{:id 1, :h {:id 1, :id2 1, :x 1, :y 1}, :x 1}]}
   {:f [{:id 2, :h {:id 4, :id2 2, :x 4, :y 2}, :x 2}
        {:id 3, :h {:id 5, :id2 3, :x 5, :y 3}, :x 3}]}]
  (hydrate [{:f [{:id 1, :h {:id 1, :id2 1}}]}
            {:f [{:id 2, :h {:id 4, :id2 2}}
                 {:id 3, :h {:id 5, :id2 3}}]}]
           [:f :x [:h :x :y]]))

;; multiple nested-nested hydrations
(expect
  [{:f [{:g {:id 1, :x 1}, :h {:i {:id2 1, :y 1}}}]}
   {:f [{:g {:id 2, :x 2}, :h {:i {:id2 2, :y 2}}}
        {:g {:id 3, :x 3}, :h {:i {:id2 3, :y 3}}}]}]
  (hydrate [{:f [{:g {:id 1}
                  :h {:i {:id2 1}}}]}
            {:f [{:g {:id 2}
                  :h {:i {:id2 2}}}
                 {:g {:id 3}
                  :h {:i {:id2 3}}}]}]
           [:f [:g :x] [:h [:i :y]]]))

;; *nasty* nested-nested hydration
(expect
  [{:f [{:id 1, :h {:id2 1, :y 1}, :x 1}
        {:id 2, :x 2}
        {:id 3, :h {:id2 3, :y 3}, :x 3}]}
   {:f {:id 1, :h {:id2 1, :y 1}, :x 1}}
   {:f [{:id 4, :x 4}
        {:id 5, :h {:id2 5, :y 5}, :x 5}
        {:id 6, :x 6}]}]
  (hydrate [{:f [{:id 1, :h {:id2 1}}
                 {:id 2}
                 {:id 3, :h {:id2 3}}]}
            {:f {:id 1, :h {:id2 1}}}
            {:f [{:id 4}
                 {:id 5, :h {:id2 5}}
                 {:id 6}]}]
           [:f :x [:h :y]]))

;; check that hydration doesn't barf if we ask it to hydrate an object that's not there
(expect {:f [:a 100]}
  (hydrate {:f [:a 100]} :p))

;;; ## BATCHED HYDRATION TESTS

;; Check that batched hydration doesn't try to hydrate fields that already exist and are not delays
(expect {:user_id 1
         :user "OK <3"}
  (hydrate {:user_id 1
            :user    "OK <3"}
           :user))

(defn- with-is-bird?
  {:batched-hydrate :is-bird?}
  [objects]
  (for [object objects]
    (assoc object :is-bird? true)))

(expect
  [{:type :toucan, :is-bird? true}
   {:type :pigeon, :is-bird? true}]
  (hydrate [{:type :toucan}
            {:type :pigeon}]
           :is-bird?))
