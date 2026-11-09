(ns rontolisp.internal.cljs-core
  "The cljs.core names a ClojureScript port reads that clojure.core leaves out,
  built into rontolisp: the protocols no clojure.lang interface stands for, the
  functions of the protocols one does, the printing and hashing helpers a
  port's types implement their protocols with, and JavaScript's numbers and
  arrays. Loaded where a port's ns form lowers, which refers every public var
  into the port's namespace, as a ClojureScript namespace refers cljs.core.
  Written for this front end from the documented behaviour of the cljs.core
  names of the same names.")

;; the protocols no core verb here reads: a port's types implement them, and
;; only the port's own calls reach their methods (no sequence chunks here)

(defprotocol ICloneable
  (-clone [value]))

(defprotocol IPrintWithWriter
  (-pr-writer [o writer opts]))

(defprotocol IChunk
  (-drop-first [coll]))

(defprotocol IChunkedSeq
  (-chunked-first [coll])
  (-chunked-rest [coll]))

(defprotocol IChunkedNext
  (-chunked-next [coll]))

(defn clone
  "The copy value's -clone answers."
  [value]
  (-clone value))

;; the functions of the protocols a clojure.lang interface stands for: a port's
;; type implements the interface, and these reach it through the verbs reading
;; the interface, or a seq's own methods

(defn -seq [coll] (seq coll))

(defn -count [coll] (count coll))

(defn -nth
  ([coll n] (nth coll n))
  ([coll n not-found] (nth coll n not-found)))

(defn -lookup
  ([o k] (get o k))
  ([o k not-found] (get o k not-found)))

(defn -reduce
  ([coll f] (reduce f coll))
  ([coll f start] (reduce f start coll)))

(defn -kv-reduce [coll f init] (reduce-kv f init coll))

(defn -deref [o] (deref o))

(defn -meta [o] (meta o))

(defn -with-meta [o meta] (with-meta o meta))

(defn -conj [coll o] (conj coll o))

(defn -empty [coll] (empty coll))

(defn -equiv [o other] (= o other))

(defn -hash [o] (hash o))

(defn -disjoin [coll v] (disj coll v))

(defn -assoc [coll k v] (assoc coll k v))

(defn -contains-key? [coll k] (contains? coll k))

(defn -find [coll k] (find coll k))

(defn -dissoc [coll k] (dissoc coll k))

(defn -peek [coll] (peek coll))

(defn -pop [coll] (pop coll))

(defn -first
  "The first member of a seq, through its own method, so a port's seq type
  answers what it answers itself."
  [coll]
  (.first coll))

(defn -rest
  "The rest of a seq, through its own method."
  [coll]
  (.more coll))

(defn -next
  "The next of a seq, through its own method: a port's seq type steps to the
  value it makes, which the verbs reading it as a seq would rewrap."
  [coll]
  (.next coll))

(defn -rseq [coll] (rseq coll))

(defn -realized? [x] (realized? x))

(defn -as-transient [coll] (transient coll))

(defn -conj! [tcoll val] (conj! tcoll val))

(defn -persistent! [tcoll] (persistent! tcoll))

(defn -disjoin! [tcoll v] (disj! tcoll v))

(defn -assoc! [tcoll k v] (assoc! tcoll k v))

(defn -dissoc! [tcoll k] (dissoc! tcoll k))

(defn -pop! [tcoll] (pop! tcoll))

(defn -compare [x y] (compare x y))

(defn -reset! [o new-value] (reset! o new-value))

(defn lookup-contains?
  "Whether coll's lookup answers anything for k: cljs.core's contains?, which a
  port's set type's contains is."
  [coll k]
  (not (identical? (get coll k ::missing) ::missing)))

;; printing: a type implementing IPrintWithWriter prints through it wherever
;; the printer meets it, as cljs.core's printer prints it

(defmethod print-method rontolisp.internal.cljs_core.IPrintWithWriter [o writer]
  (-pr-writer o writer nil))

(defn -write
  "Writes s to writer."
  [writer s]
  (.write writer s))

(defn pr-writer
  "Writes obj to writer as pr writes it."
  [obj writer opts]
  (print-method obj writer))

(defn pr-str*
  "obj as pr writes it: what a port's toString answers."
  [obj]
  (pr-str obj))

(defn pr-sequential-writer
  "Writes coll's members to writer between begin and end, sep between them,
  each through print-one, cut at *print-length* with ..., and # past
  *print-level*, as clojure.core's own sequential printer cuts them."
  [writer print-one begin sep end opts coll]
  (if (and *print-level* (<= *print-level* 0))
    (.write writer "#")
    (binding [*print-level* (and *print-level* (dec *print-level*))]
      (.write writer begin)
      (loop [xs (seq coll) n *print-length* first? true]
        (when xs
          (when-not first?
            (.write writer sep))
          (if (and n (zero? n))
            (.write writer "...")
            (do (print-one (first xs) writer opts)
                (recur (next xs) (and n (dec n)) false)))))
      (.write writer end))))

;; equality and hashing

(defn equiv-sequential
  "Whether y is sequential and holds x's members in order, compared by =."
  [x y]
  (boolean
    (when (sequential? y)
      (if (and (counted? x) (counted? y) (not (== (count x) (count y))))
        false
        (loop [xs (seq x) ys (seq y)]
          (cond (nil? xs) (nil? ys)
                (nil? ys) false
                (= (first xs) (first ys)) (recur (next xs) (next ys))
                :else false))))))

(defmacro caching-hash
  "The hash cached in the mutable field hash-key, computed by hash-fn of coll
  and stored there the first time."
  [coll hash-fn hash-key]
  `(let [h# ~hash-key]
     (if-not (nil? h#)
       h#
       (let [h# (~hash-fn ~coll)]
         (set! ~hash-key h#)
         h#))))

;; a chunk over an array, the one cljs.core's chunked seqs hand out

(deftype ArrayChunk [arr off end]
  clojure.lang.Counted
  (count [_] (- end off))
  clojure.lang.Indexed
  (nth [_ i] (aget arr (+ off i)))
  (nth [_ i not-found]
    (if (and (>= i 0) (< i (- end off)))
      (aget arr (+ off i))
      not-found))
  IChunk
  (-drop-first [_]
    (if (== off end)
      (throw (RuntimeException. "-drop-first of empty chunk"))
      (ArrayChunk. arr (inc off) end)))
  clojure.lang.IReduce
  (reduce [this f]
    (if (== off end)
      (f)
      (reduce f (aget arr off) (ArrayChunk. arr (inc off) end))))
  (reduce [_ f start]
    (loop [acc start n off]
      (if (< n end)
        (let [acc (f acc (aget arr n))]
          (if (reduced? acc)
            @acc
            (recur acc (inc n))))
        acc))))

;; JavaScript's numbers and arrays

(defn js-number
  "x as a JavaScript number reads in ClojureScript: a double holding a whole
  number a double holds exactly is that integer, which cljs prints as one;
  anything else is itself."
  [x]
  (if (and (double? x) (not (NaN? x)) (not (infinite? x)) (== x (Math/floor x))
           (<= (Math/abs x) 9007199254740992.0))
    (long x)
    x))

(defn js-slice
  "(.slice x start end) of a JavaScript array or string: its members from start
  up to end, a negative index counting from the end, each clamped to x."
  ([x start] (js-slice x start (count x)))
  ([x start end]
   (let [n (count x)
         clamp (fn [i] (if (neg? i) (max 0 (+ n i)) (min i n)))
         from (clamp start)
         to (max from (clamp end))]
     (if (string? x)
       (subs x from to)
       (let [out (object-array (- to from))]
         (dotimes [i (- to from)]
           (aset out i (aget x (+ from i))))
         out)))))
