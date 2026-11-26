;; collections.clj -- the persistent collections and the types a program builds on
;; them: map order, records, keys found by a type's own equality, hashes, transients,
;; queues, the collection interfaces, destructuring, case and condp, iteration.
;;
;;   rontolisp collections.clj
;;   rontolisp collections.clj -o Collections.class && java Collections
;;   rontolisp collections.clj -o collections.wasm && wasmtime run collections.wasm
;;   rontolisp collections.clj -o collections-c.wasm --component && wasmtime run collections-c.wasm
(ns collections)

(println "== maps")
;; a small map walks in the order its keys arrived, through seq, keys and vals alike
(let [m (-> {} (assoc :z 1) (assoc :a 2) (assoc :m 3) (dissoc :a) (assoc :b 4))]
  (println m (keys m) (vals m) (first m)))
;; building a large map one assoc at a time shares structure with the previous one
(let [big (reduce #(assoc %1 %2 (* %2 %2)) {} (range 20000))
      bigger (assoc big :extra true)]
  (println (count big) (count bigger) (get big 19999) (contains? big :extra)))
(println (merge-with + {:a 1 :b 2} {:b 10 :c 20}) (update-keys {:a 1} name) (update-vals {:a 1} inc))
(println (into (sorted-map-by >) {1 :a 3 :c 2 :b}) (subseq (sorted-set 5 1 9 3 7) >= 3 < 9))

(println "== records")
(defrecord Point [x y])
(let [p (map->Point {:x 1 :y 2 :label "origin+"})]
  (println p (seq p) (:label p) (into {} (dissoc p :label))))

;; a deftype keying a map and a set by its own hash and equality
(deftype Caseless [s]
  clojure.lang.IHashEq
  (hasheq [_] (hash (clojure.string/lower-case s)))
  Object
  (equals [_ o] (and (instance? Caseless o)
                     (= (clojure.string/lower-case s) (clojure.string/lower-case (.-s ^Caseless o)))))
  (hashCode [_] (.hashCode (clojure.string/lower-case s)))
  (toString [_] s))

(let [m {(Caseless. "Hello") 1}]
  (println (get m (Caseless. "HELLO")) (count (conj #{(Caseless. "a")} (Caseless. "A")))))

(println "== hashes")
(println (hash "abc") (hash :k) (hash [1 2 3]) (hash {:a 1}) (= (hash [1 2]) (hash '(1 2))))

(println "== transients and queues")
(let [v (persistent! (reduce conj! (transient []) (range 5)))
      m (persistent! (-> (transient {}) (assoc! :a 1) (assoc! :b 2) (dissoc! :a)))]
  (println v m))
(let [q (-> clojure.lang.PersistentQueue/EMPTY (conj 1) (conj 2) (conj 3))]
  (println (peek q) (vec (pop q)) (count q) (seq (into q [4 5]))))

(println "== the collection interfaces")
;; a deftype answering count, get and seq as the core verbs ask them
(deftype Window [items start end]
  clojure.lang.Counted
  (count [_] (- end start))
  clojure.lang.ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [_ k not-found] (if (and (integer? k) (< -1 k (- end start))) (nth items (+ start k)) not-found))
  clojure.lang.Seqable
  (seq [_] (seq (subvec items start end))))

(let [w (Window. [:a :b :c :d :e] 1 4)]
  (println (count w) (get w 0) (get w 5 :none) (seq w) (map name w)))

;; reify a reducible: reduce asks it to fold itself
(let [evens (reify clojure.lang.IReduceInit
              (reduce [_ f init]
                (loop [acc init n 0]
                  (if (> n 10) acc (let [r (f acc n)] (if (reduced? r) @r (recur r (+ n 2))))))))]
  (println (reduce + 0 evens) (reduce (fn [acc x] (if (> x 4) (reduced acc) (conj acc x))) [] evens)))

(println "== destructuring")
(defn connect [host & {:keys [port tls] :or {port 80 tls false}}]
  (str host ":" port (when tls " (tls)")))
(println (connect "a.example") (connect "b.example" :port 443 :tls true) (connect "c.example" {:port 8080}))
(let [{:user/keys [name roles] :as user} {:user/name "ada" :user/roles #{:admin}}
      {::keys [token]} {::token "t-1"}]
  (println name roles (count user) token))

(println "== case and condp")
(defn kind [x]
  (case x
    (1 2 3) :small
    "one" :string
    [1 2] :vector
    (:a :b) :keyword
    :other))
(println (map kind [2 "one" [1 2] :b 99]))
(println (condp some [1 2 3] #{0} :zero #{3 4} :>> inc :none)
         (condp < 7 10 :big 5 :medium :small))

(println "== iteration")
;; a paged API walked lazily: each call answers a page and the token of the next one
(defn page [token]
  (let [start (or token 0)]
    {:items (range start (min 10 (+ start 4))) :next (when (< (+ start 4) 10) (+ start 4))}))
(println (vec (mapcat identity (iteration page :vf :items :kf :next))))

(println "== byte arrays, instants and uuids")
(let [b (byte-array [72 105 -1])]
  (println (alength b) (vec b) (aget b 2) (String. (byte-array (take 2 b)))))
(let [t #inst "2026-10-12T09:30:00.000-00:00"
      u #uuid "6f1c2a9e-8b4d-4e2a-9c3b-0a1b2c3d4e5f"]
  (println (inst-ms t) (pr-str t) (uuid? u) (pr-str u) (= u (parse-uuid (str u)))))
