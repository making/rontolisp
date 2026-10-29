(ns rontolisp.internal.core-macros
  "The expansions of the clojure.core macros, built into rontolisp: what
  macroexpand-1, macroexpand and a core macro's var answer for a call of one,
  as data. A program's own calls of these macros lower directly and run none of
  this. Each expander takes the call and its environment ahead of the call's
  arguments, like the oracle's macro functions, and answers the oracle's form:
  the special forms let*, loop*, letfn*, fn* and case* under the binding
  macros, if and do under the conditionals. Written for this front end from
  the documented behaviour of the macros of the same names and diffed against
  their expansions on clj 1.12.6; destructure is clojure.core's own function.")

(defn- push
  "The vector v with xs after its members: every vector these expansions build
  grows through it (a conj at each site would lower its every collection arm
  there)."
  [v & xs]
  (vec (concat v xs)))

(defn- refuse
  "Throws the refusal of a malformed call of the macro the form calls."
  [form requirement]
  (throw (IllegalArgumentException. (str (first form) " requires " requirement))))

(defn- binding-vector
  "bindings, refused unless it is a vector of an even count."
  [form bindings]
  (when-not (vector? bindings)
    (refuse form "a vector for its binding"))
  (when-not (even? (count bindings))
    (refuse form "an even number of forms in binding vector"))
  bindings)

(defn- single-binding
  "bindings, refused unless it is a vector of one name and one init."
  [form bindings]
  (when-not (vector? bindings)
    (refuse form "a vector for its binding"))
  (when-not (= 2 (count bindings))
    (refuse form "exactly 2 forms in binding vector"))
  bindings)

(defn- no-match
  "The form throwing what a case or condp no clause took throws."
  [value]
  (list 'throw (list 'java.lang.IllegalArgumentException.
                     (list 'clojure.core/str "No matching clause: " value))))

;; destructure: bind-pattern, below, is in reach of the definitions above it

(defn- bind-vector
  "ret followed by the bindings of the vector pattern pat over v: v bound to a
  fresh vec__ name, each positional name to its nth member -- or, behind an &,
  every member walked off a seq__ name, the rest bound to what remains -- and
  the name after :as to the whole, which ends the pattern."
  [ret pat v]
  (let [whole (gensym "vec__")
        rest? (some #(= '& %) pat)
        walked (when rest? (gensym "seq__"))
        member (when rest? (gensym "first__"))]
    (loop [ret (if rest?
                 (push ret whole v walked (list 'clojure.core/seq whole))
                 (push ret whole v))
           n 0
           items (seq pat)
           after-rest false]
      (if-not items
        ret
        (let [item (first items)]
          (cond
            (= item '&) (recur (bind-pattern ret (second items) walked) n (nnext items) true)
            (= item :as) (bind-pattern ret (second items) whole)
            after-rest (throw (Exception. "Unsupported binding form, only :as can follow & parameter"))
            rest? (recur (bind-pattern (push ret
                                             member (list 'clojure.core/first walked)
                                             walked (list 'clojure.core/next walked))
                                       item member)
                         (inc n) (next items) after-rest)
            :else (recur (bind-pattern ret item (list 'clojure.core/nth whole n nil))
                         (inc n) (next items) after-rest)))))))

(defn- names-entry?
  "Whether a map pattern's key introduces a vector of names: :keys, :strs or
  :syms, plain or namespaced."
  [k]
  (and (keyword? k) (let [n (name k)] (or (= n "keys") (= n "strs") (= n "syms")))))

(defn- key-of
  "The key a name of a :keys, :strs or :syms vector looks up, the vector's own
  namespace first."
  [entry n]
  (let [ns (or (namespace entry) (namespace n))]
    (case (name entry)
      "keys" (keyword ns (name n))
      "syms" (list 'quote (symbol ns (name n)))
      "strs" (str n))))

(defn- put-entry
  "entries, a vector of [form key] pairs, binding form to key: in place when
  form is bound already, else at the end."
  [entries form k]
  (loop [i 0]
    (cond
      (= i (count entries)) (push entries [form k])
      (= form (first (nth entries i))) (assoc entries i [form k])
      :else (recur (inc i)))))

(defn- map-entries
  "The [form key] pairs a map pattern binds, in the order map destructuring
  binds them: its own form-key entries as they stand, then the names of each
  :keys, :strs and :syms vector in turn."
  [pat]
  (let [own (reduce (fn [entries [k v]]
                      (if (or (= k :as) (= k :or) (names-entry? k))
                        entries
                        (put-entry entries k v)))
                    [] pat)]
    (reduce (fn [entries [k names]]
              (if (names-entry? k)
                (reduce (fn [entries n] (put-entry entries n (key-of k n))) entries names)
                entries))
            own pat)))

(defn- map-coercion
  "The form turning the value bound to m into the map a map pattern reads: a
  seq of keys and values into their map, a seq of one map into that map, an
  empty seq into the empty map, any other value as it is."
  [m]
  (list 'if (list 'clojure.core/seq? m)
        (list 'if (list 'clojure.core/next m)
              (list 'clojure.lang.PersistentArrayMap/createAsIfByAssoc (list 'clojure.core/to-array m))
              (list 'if (list 'clojure.core/seq m)
                    (list 'clojure.core/first m)
                    'clojure.lang.PersistentArrayMap/EMPTY))
        m))

(defn- bind-map
  "ret followed by the bindings of the map pattern pat over v: v bound to a
  fresh map__ name and coerced to a map, the name after :as to it, then each
  bound form to its key's value, its :or default behind it."
  [ret pat v]
  (let [m (gensym "map__")
        defaults (:or pat)
        ret (push ret m v m (map-coercion m))
        ret (if-let [whole (:as pat)] (push ret whole m) ret)]
    (reduce (fn [ret [form k]]
              (let [local (if (ident? form) (with-meta (symbol (name form)) (meta form)) form)
                    value (if (contains? defaults local)
                            (list 'clojure.core/get m k (get defaults local))
                            (list 'clojure.core/get m k))]
                (if (ident? form)
                  (push ret local value)
                  (bind-pattern ret form value))))
            ret (map-entries pat))))

(defn- bind-pattern
  "ret followed by the bindings of the pattern pat over the form v."
  [ret pat v]
  (cond
    (symbol? pat) (push ret pat v)
    (vector? pat) (bind-vector ret pat v)
    (map? pat) (bind-map ret pat v)
    :else (throw (Exception. (str "Unsupported binding form: " pat)))))

(defn destructure
  "The binding vector bindings with every destructuring pattern in it replaced
  by the plain bindings that take it apart: bindings itself when every pattern
  is a name."
  [bindings]
  (let [pairs (partition 2 bindings)]
    (if (every? symbol? (map first pairs))
      bindings
      (reduce (fn [ret [pat v]] (bind-pattern ret pat v)) [] pairs))))

;; binding forms

(defn expand-let
  "(let [bindings*] body*): let* over the destructured bindings."
  [&form &env bindings & body]
  (binding-vector &form bindings)
  `(let* ~(destructure bindings) ~@body))

(defn expand-loop
  "(loop [bindings*] body*): loop* over plain names; over patterns, a let
  binding each pattern's init to a fresh name, the loop* over the names, and a
  let taking the patterns apart again inside it."
  [&form &env bindings & body]
  (binding-vector &form bindings)
  (let [pairs (partition 2 bindings)]
    (if (every? symbol? (map first pairs))
      `(loop* ~bindings ~@body)
      (let [named (map (fn [[pat v]] [pat (if (symbol? pat) pat (gensym)) v]) pairs)]
        `(let ~(vec (mapcat (fn [[pat g v]] (if (= pat g) [pat v] [g v pat g])) named))
           (loop* ~(vec (mapcat (fn [[_ g _]] [g g]) named))
             (let ~(vec (mapcat (fn [[pat g _]] [pat g]) named))
               ~@body)))))))

(defn- fn-conditions
  "body with a leading :pre/:post map (one followed by more body) turned into
  asserts: the :pre ones ahead of the body, the :post ones over % bound to its
  value."
  [body]
  (let [conditions (when (and (next body) (map? (first body))) (first body))
        body (if conditions (next body) body)
        pre (:pre conditions)
        post (:post conditions)
        body (if post
               `((let [~'% ~(if (next body) `(do ~@body) (first body))]
                   ~@(map (fn [c] `(assert ~c)) post)
                   ~'%))
               body)]
    (if pre
      (concat (map (fn [c] `(assert ~c)) pre) body)
      body)))

(defn- fn-arity
  "One arity of fn as fn* takes it, ([params] body*): each parameter pattern
  replaced by a fresh p__ name, the patterns bound in a let around the body."
  [arity]
  (let [params (first arity)
        body (fn-conditions (next arity))]
    (when-not (vector? params)
      (throw (IllegalArgumentException. (str "Parameter declaration " params " should be a vector"))))
    (if (every? symbol? params)
      (cons params body)
      (loop [ps (seq params) plain [] lets []]
        (if ps
          (let [p (first ps)]
            (if (symbol? p)
              (recur (next ps) (push plain p) lets)
              (let [g (gensym "p__")]
                (recur (next ps) (push plain g) (push lets p g)))))
          `(~(with-meta plain (meta params)) (let ~lets ~@body)))))))

(defn expand-fn
  "(fn name? [params*] body*) or (fn name? ([params*] body*)+): fn* over its
  arities."
  [&form &env & sigs]
  (let [name (when (symbol? (first sigs)) (first sigs))
        sigs (if name (next sigs) sigs)
        sigs (cond
               (vector? (first sigs)) (list sigs)
               (seq? (first sigs)) sigs
               (seq sigs) (throw (IllegalArgumentException.
                                  (str "Parameter declaration " (first sigs) " should be a vector")))
               :else (throw (IllegalArgumentException. "Parameter declaration missing")))
        arities (map fn-arity sigs)]
    (with-meta (if name (list* 'fn* name arities) (cons 'fn* arities))
      (meta &form))))

(defn expand-letfn
  "(letfn [(name [params*] body*)*] body*): letfn* binding each name to its fn."
  [&form &env fnspecs & body]
  `(letfn* ~(vec (mapcat (fn [spec] [(first spec) `(fn ~@spec)]) fnspecs)) ~@body))

;; conditionals

(defn expand-when
  "(when test body*): if over a do."
  [&form &env test & body]
  (list 'if test (cons 'do body)))

(defn expand-when-not
  "(when-not test body*): if answering nil when test holds."
  [&form &env test & body]
  (list 'if test nil (cons 'do body)))

(defn expand-if-not
  "(if-not test then else?): if over the negated test."
  ([&form &env test then] `(if-not ~test ~then nil))
  ([&form &env test then else] `(if (not ~test) ~then ~else)))

(defn expand-cond
  "(cond test expr ...): if over the first pair, cond over the rest."
  [&form &env & clauses]
  (when clauses
    (list 'if (first clauses)
          (if (next clauses)
            (second clauses)
            (throw (IllegalArgumentException. "cond requires an even number of forms")))
          (cons 'clojure.core/cond (nnext clauses)))))

(defn expand-and
  "(and x*): true of none, x of one, else x bound and tested."
  ([&form &env] true)
  ([&form &env x] x)
  ([&form &env x & more] `(let [and# ~x] (if and# (and ~@more) and#))))

(defn expand-or
  "(or x*): nil of none, x of one, else x bound and tested."
  ([&form &env] nil)
  ([&form &env x] x)
  ([&form &env x & more] `(let [or# ~x] (if or# or# (or ~@more)))))

(defn- condp-clauses
  "The tests of condp's clauses over the bound predicate and value: a test and
  an expression, a test :>> and a function of the predicate's answer, a lone
  default, or the no-match refusal when none is left."
  [pred value clauses]
  (let [clause (take (if (= :>> (second clauses)) 3 2) clauses)
        more (drop (count clause) clauses)]
    (case (count clause)
      0 (no-match value)
      1 (first clause)
      2 `(if (~pred ~(first clause) ~value)
           ~(second clause)
           ~(condp-clauses pred value more))
      `(if-let [p# (~pred ~(first clause) ~value)]
         (~(nth clause 2) p#)
         ~(condp-clauses pred value more)))))

(defn expand-condp
  "(condp pred expr clause* default?): the predicate and the value bound to
  fresh names, then each clause's test in turn."
  [&form &env pred expr & clauses]
  (let [p (gensym "pred__")
        value (gensym "expr__")]
    `(let [~p ~pred ~value ~expr] ~(condp-clauses p value clauses))))

(defn expand-if-let
  "(if-let [form test] then else?): test bound to a fresh name, form bound to
  it when it holds."
  ([&form &env bindings then] `(if-let ~bindings ~then nil))
  ([&form &env bindings then else & more]
   (single-binding &form bindings)
   (when more
     (refuse &form "1 or 2 forms after binding vector"))
   `(let [temp# ~(nth bindings 1)]
      (if temp# (let [~(nth bindings 0) temp#] ~then) ~else))))

(defn expand-when-let
  "(when-let [form test] body*): when over test bound to a fresh name."
  [&form &env bindings & body]
  (single-binding &form bindings)
  `(let [temp# ~(nth bindings 1)]
     (when temp# (let [~(nth bindings 0) temp#] ~@body))))

(defn expand-if-some
  "(if-some [form test] then else?): like if-let, testing for nil only."
  ([&form &env bindings then] `(if-some ~bindings ~then nil))
  ([&form &env bindings then else & more]
   (single-binding &form bindings)
   (when more
     (refuse &form "1 or 2 forms after binding vector"))
   `(let [temp# ~(nth bindings 1)]
      (if (nil? temp#) ~else (let [~(nth bindings 0) temp#] ~then)))))

(defn expand-when-some
  "(when-some [form test] body*): like when-let, testing for nil only."
  [&form &env bindings & body]
  (single-binding &form bindings)
  `(let [temp# ~(nth bindings 1)]
     (if (nil? temp#) nil (let [~(nth bindings 0) temp#] ~@body))))

(defn expand-when-first
  "(when-first [form coll] body*): form bound to the first member when coll
  has one."
  [&form &env bindings & body]
  (single-binding &form bindings)
  `(when-let [xs# (seq ~(nth bindings 1))]
     (let [~(nth bindings 0) (first xs#)] ~@body)))

(defn- case-hash
  "The hash a case test dispatches on: its Java hashCode, 0 for nil."
  [x]
  (if (nil? x) 0 (.hashCode x)))

(defn- shift-mask
  "x shifted right by shift and masked by mask."
  [shift mask x]
  (bit-and (bit-shift-right x shift) mask))

(defn- fits-table?
  "Whether the ints span less than a jump table's 8192 entries."
  [ints]
  (< (- (apply max ints) (apply min ints)) 8192))

(defn- min-hash
  "The [shift mask] sending the ints to distinct values, masks widening from 1
  bit to 13 and shifts from 0 to 30 within each, or nil when none does."
  [ints]
  (loop [bits 1 shift 0]
    (cond
      (> bits 13) nil
      (> shift 30) (recur (inc bits) 0)
      :else (let [mask (dec (bit-shift-left 1 bits))]
              (if (apply distinct? (map #(shift-mask shift mask %) ints))
                [shift mask]
                (recur bits (inc shift)))))))

(defn- case-key
  "The key a test dispatches on: the int itself for :int, else its hash -- a
  merged test, the int hash its tests share, as itself."
  [mode merged test]
  (cond
    (= mode :int) (int test)
    (contains? merged test) test
    :else (case-hash test)))

(defn- case-map
  "The map of each test's key, shifted and masked by a nonzero mask, to its
  [test then], its keys ascending."
  [mode merged shift mask tests thens]
  (reduce (fn [m [k entry]] (assoc m k entry))
          {} (sort-by first (map (fn [test then]
                                   (let [k (case-key mode merged test)]
                                     [(if (zero? mask) k (shift-mask shift mask k))
                                      [(if (= mode :int) (int test) test) then]]))
                                 tests thens))))

(defn- table-of
  "[shift mask case-map switch-type] over the distinct keys of the tests: a
  compact table over the keys as they are when they fit one, else over the keys
  shifted and masked apart, else a sparse table."
  [mode merged tests thens]
  (let [keys (map #(case-key mode merged %) tests)]
    (if (fits-table? keys)
      [0 0 (case-map mode merged 0 0 tests thens) :compact]
      (let [[shift mask] (or (min-hash keys) [0 0])]
        (if (zero? mask)
          [0 0 (case-map mode merged 0 0 tests thens) :sparse]
          [shift mask (case-map mode merged shift mask tests thens) :compact])))))

(defn- merge-collisions
  "[tests thens skip-check]: each set of tests sharing a hash merged into one
  test, the hash itself, whose then is the condp of those tests ending in
  default; skip-check the set of those hashes."
  [value default tests thens]
  (let [buckets (reduce (fn [buckets [test then]]
                          (let [h (case-hash test)
                                bucket (some (fn [[k b]] (when (= k h) b)) buckets)]
                            (put-entry buckets h (push bucket [test then]))))
                        [] (map vector tests thens))
        merged (map (fn [[h bucket]]
                      (if (= 1 (count bucket))
                        (first bucket)
                        [h `(condp = ~value ~@(mapcat (fn [[test then]] [(list 'quote test) then]) bucket)
                              ~default)]))
                    buckets)]
    [(map first merged) (map second merged)
     (set (map first (filter (fn [[_ bucket]] (< 1 (count bucket))) buckets)))]))

(defn- hashes-table
  "[shift mask case-map switch-type skip-check] of tests dispatched on their
  hashes, colliding tests merged first (merged holds the merged tests' hashes)."
  [value default tests thens merged]
  (if (apply distinct? (map #(case-key :hash merged %) tests))
    (push (table-of :hash merged tests thens) nil)
    (let [[tests thens skip-check] (merge-collisions value default tests thens)
          [shift mask cases switch-type] (hashes-table value default tests thens skip-check)]
      [shift mask cases switch-type
       (if (zero? mask) skip-check (set (map #(shift-mask shift mask %) skip-check)))])))

(defn expand-case
  "(case e clause* default?): e bound to a fresh name, then case* over a map
  of each test's case key to its [test then] -- the tests themselves when all
  are ints, else their hashes -- a list of tests counting each."
  [&form &env e & clauses]
  (let [g (gensym)
        default (if (odd? (count clauses)) (last clauses) (no-match g))]
    (if (> 2 (count clauses))
      `(let [~g ~e] ~default)
      (let [pairs (reduce (fn [pairs [test then]]
                            (reduce (fn [pairs t]
                                      (when (some #(= t (first %)) pairs)
                                        (throw (IllegalArgumentException.
                                                (str "Duplicate case test constant: " t))))
                                      (push pairs [t then]))
                                    pairs (if (seq? test) test [test])))
                          [] (partition 2 clauses))
            tests (map first pairs)
            thens (map second pairs)]
        (cond
          (every? #(and (integer? %) (<= -2147483648 % 2147483647)) tests)
          (let [[shift mask cases switch-type] (table-of :int nil tests thens)]
            `(let [~g ~e] (case* ~g ~shift ~mask ~default ~cases ~switch-type :int)))
          (every? keyword? tests)
          (let [[shift mask cases switch-type skip-check] (hashes-table g default tests thens nil)]
            `(let [~g ~e] (case* ~g ~shift ~mask ~default ~cases ~switch-type :hash-identity ~skip-check)))
          :else
          (let [[shift mask cases switch-type skip-check] (hashes-table g default tests thens nil)]
            `(let [~g ~e] (case* ~g ~shift ~mask ~default ~cases ~switch-type :hash-equiv ~skip-check))))))))

;; threading

(defn- thread-into
  "form with x threaded in as its first argument, or its last when last? -- a
  form that is no seq called with x -- keeping form's metadata."
  [x form last?]
  (if (seq? form)
    (with-meta (if last? `(~@form ~x) `(~(first form) ~x ~@(next form))) (meta form))
    (list form x)))

(defn expand-->
  "(-> x form*): x threaded through each form as its first argument."
  [&form &env x & forms]
  (reduce (fn [x form] (thread-into x form false)) x forms))

(defn expand-->>
  "(->> x form*): x threaded through each form as its last argument."
  [&form &env x & forms]
  (reduce (fn [x form] (thread-into x form true)) x forms))

(defn expand-as->
  "(as-> expr name form*): name bound to expr, then to each form in turn."
  [&form &env expr name & forms]
  `(let [~name ~expr ~@(interleave (repeat name) (butlast forms))]
     ~(if (empty? forms) name (last forms))))

(defn- threaded-let
  "A let binding g to expr, then to each of the step forms over it."
  [g expr steps]
  `(let [~g ~expr ~@(interleave (repeat g) (butlast steps))]
     ~(if (empty? steps) g (last steps))))

(defn- cond-threaded
  "cond-> or cond->> through thread, a symbol: each step taken when its test holds."
  [expr clauses thread]
  (when-not (even? (count clauses))
    (throw (AssertionError. "Assert failed: (even? (count clauses))")))
  (let [g (gensym)]
    (threaded-let g expr (map (fn [[test step]] `(if ~test (~thread ~g ~step) ~g)) (partition 2 clauses)))))

(defn expand-cond->
  "(cond-> expr test form ...): expr threaded as the first argument through each
  form whose test holds."
  [&form &env expr & clauses]
  (cond-threaded expr clauses 'clojure.core/->))

(defn expand-cond->>
  "(cond->> expr test form ...): expr threaded as the last argument through
  each form whose test holds."
  [&form &env expr & clauses]
  (cond-threaded expr clauses 'clojure.core/->>))

(defn expand-some->
  "(some-> expr form*): expr threaded as the first argument while not nil."
  [&form &env expr & forms]
  (let [g (gensym)]
    (threaded-let g expr (map (fn [step] `(if (nil? ~g) nil (-> ~g ~step))) forms))))

(defn expand-some->>
  "(some->> expr form*): expr threaded as the last argument while not nil."
  [&form &env expr & forms]
  (let [g (gensym)]
    (threaded-let g expr (map (fn [step] `(if (nil? ~g) nil (->> ~g ~step))) forms))))

(defn expand-doto
  "(doto x form*): x bound to a fresh name, each form called with it as its
  first argument, then the name."
  [&form &env x & forms]
  (let [g (gensym)]
    `(let [~g ~x]
       ~@(map (fn [form]
                (with-meta (if (seq? form) `(~(first form) ~g ~@(next form)) `(~form ~g)) (meta form)))
              forms)
       ~g)))

(defn expand-dotdot
  "(.. x form+): the . form of x and the first form, .. over the rest."
  ([&form &env x form] (list '. x form))
  ([&form &env x form & more] (list* '.. (list '. x form) more)))

;; loops

(defn expand-dotimes
  "(dotimes [name n] body*): a loop over name from 0 below n."
  [&form &env bindings & body]
  (single-binding &form bindings)
  `(let [n# (long ~(nth bindings 1))]
     (loop [~(nth bindings 0) 0]
       (when (< ~(nth bindings 0) n#)
         ~@body
         (recur (unchecked-inc ~(nth bindings 0)))))))

(defn expand-while
  "(while test body*): a loop running the body while test holds."
  [&form &env test & body]
  `(loop [] (when ~test ~@body (recur))))

(defn- doseq-step
  "[recurs? form]: the doseq form over the remaining binding steps around the
  body, recurs? whether the step above must put its recur behind it. A
  collection step is a loop over the collection's seq with the chunked walk's
  four names, the chunk taken through the one name c every level shares;
  :let, :while and :when wrap what follows."
  [c recur-form steps body]
  (if-not (seq steps)
    [true `(do ~@body)]
    (let [k (first steps)
          v (second steps)
          more (nnext steps)]
      (if (keyword? k)
        (let [[recurs inner] (doseq-step c recur-form more body)]
          (case k
            :let [recurs `(let ~v ~inner)]
            :while [false `(when ~v ~inner ~@(when recurs [recur-form]))]
            :when [false `(if ~v (do ~inner ~@(when recurs [recur-form])) ~recur-form)]))
        (let [walked (gensym "seq_")
              chunk (gensym "chunk_")
              size (gensym "count_")
              i (gensym "i_")
              recur-seq `(recur (next ~walked) nil 0 0)
              [recurs inner] (doseq-step c recur-seq more body)
              recur-chunk `(recur ~walked ~chunk ~size (unchecked-inc ~i))
              [_ inner-chunk] (doseq-step c recur-chunk more body)]
          [true
           `(loop [~walked (seq ~v) ~chunk nil ~size 0 ~i 0]
              (if (< ~i ~size)
                (let [~k (.nth ~chunk ~i)]
                  ~inner-chunk
                  ~@(when recurs [recur-chunk]))
                (when-let [~walked (seq ~walked)]
                  (if (chunked-seq? ~walked)
                    (let [~c (chunk-first ~walked)]
                      (recur (chunk-rest ~walked) ~c (int (count ~c)) (int 0)))
                    (let [~k (first ~walked)]
                      ~inner
                      ~@(when recurs [recur-seq]))))))])))))

(defn expand-doseq
  "(doseq [binding* ] body*): the body run for each binding of the nested walk,
  :let, :while and :when modifiers included."
  [&form &env seq-exprs & body]
  (binding-vector &form seq-exprs)
  (second (doseq-step `c# nil (seq seq-exprs) body)))

(defn- for-levels
  "The levels of a for binding vector: each a [name coll] pair followed by the
  [keyword form] pairs of its modifiers."
  [seq-exprs]
  (reduce (fn [levels [k v]]
            (if (keyword? k)
              (push (pop levels) (push (peek levels) [k v]))
              (push levels [[k v]])))
          [] (partition 2 seq-exprs)))

(defn- for-modifiers
  "inner under a level's modifiers: :let binding, :while ending the level,
  :when skipping to skip."
  [modifiers skip inner]
  (if-let [[k v] (first modifiers)]
    (let [rest-form (for-modifiers (next modifiers) skip inner)]
      (case k
        :let `(let ~v ~rest-form)
        :while `(when ~v ~rest-form)
        :when `(if ~v ~rest-form ~skip)
        (throw (IllegalArgumentException. (str "Invalid 'for' keyword " k)))))
    inner))

(defn- for-level
  "The lazy seq function of the first of levels: a walk of its collection with
  fresh iter__ and s__ names, an outer level concatenating the seqs of the next
  level's function (bound to the shared iterys name, its seq to fs), the
  innermost level answering body for each member, chunk by chunk through the
  shared c and size names and fresh i__ and b__ names where the seq is
  chunked."
  [shared levels body]
  (let [[[bind _] & modifiers] (first levels)
        more (next levels)
        iter (gensym "iter__")
        xs (gensym "s__")
        {:keys [iterys fs c size]} shared]
    (if more
      `(fn ~iter [~xs]
         (lazy-seq
          (loop [~xs ~xs]
            (when-first [~bind ~xs]
              ~(for-modifiers modifiers `(recur (rest ~xs))
                              `(let [~iterys ~(for-level shared more body)
                                     ~fs (seq (~iterys ~(second (first (first more)))))]
                                 (if ~fs
                                   (concat ~fs (~iter (rest ~xs)))
                                   (recur (rest ~xs)))))))))
      (let [i (gensym "i__")
            b (gensym "b__")]
        `(fn ~iter [~xs]
           (lazy-seq
            (loop [~xs ~xs]
              (when-let [~xs (seq ~xs)]
                (if (chunked-seq? ~xs)
                  (let [~c (chunk-first ~xs)
                        ~size (int (count ~c))
                        ~b (chunk-buffer ~size)]
                    (if (loop [~i (int 0)]
                          (if (< ~i ~size)
                            (let [~bind (.nth ~c ~i)]
                              ~(for-modifiers modifiers `(recur (unchecked-inc ~i))
                                              `(do (chunk-append ~b ~body) (recur (unchecked-inc ~i)))))
                            true))
                      (chunk-cons (chunk ~b) (~iter (chunk-rest ~xs)))
                      (chunk-cons (chunk ~b) nil)))
                  (let [~bind (first ~xs)]
                    ~(for-modifiers modifiers `(recur (rest ~xs))
                                    `(cons ~body (~iter (rest ~xs))))))))))))))

(defn expand-for
  "(for [binding*] body): the lazy seq of body over the nested walk, :let,
  :while and :when modifiers included."
  [&form &env seq-exprs body]
  (binding-vector &form seq-exprs)
  (let [iter `iter#]
    `(let [~iter ~(for-level {:iterys `iterys# :fs `fs# :c `c# :size `size#} (for-levels seq-exprs) body)]
       (~iter ~(second seq-exprs)))))

;; definitions and the rest

(defn expand-comment
  "(comment body*): nil."
  [&form &env & body]
  nil)

(defn expand-declare
  "(declare name*): a def of each name, marked declared."
  [&form &env & names]
  `(do ~@(map #(list 'def (vary-meta % assoc :declared true)) names)))

(defn expand-defn
  "(defn name doc? attrs? [params*] body*) or with several arities: def of the
  name to its fn, the docstring, attributes and arglists in the name's
  metadata."
  [&form &env name & decl]
  (when-not (symbol? name)
    (throw (IllegalArgumentException. "First argument to defn must be a symbol")))
  (let [m (if (string? (first decl)) {:doc (first decl)} {})
        decl (if (string? (first decl)) (next decl) decl)
        m (if (map? (first decl)) (merge m (first decl)) m)
        decl (if (map? (first decl)) (next decl) decl)
        decl (if (vector? (first decl)) (list decl) decl)
        m (if (map? (last decl)) (merge m (last decl)) m)
        decl (if (map? (last decl)) (butlast decl) decl)
        m (merge {:arglists (list 'quote (map first decl))} m)]
    (list 'def (with-meta name (merge (meta name) m)) (cons 'clojure.core/fn decl))))

(defn expand-defn-
  "(defn- name decl*): defn of the name marked private."
  [&form &env name & decl]
  (list* 'clojure.core/defn (vary-meta name assoc :private true) decl))

(defn expand-binding
  "(binding [var init ...] body*): the thread bindings pushed around the body."
  [&form &env bindings & body]
  (binding-vector &form bindings)
  `(let []
     (push-thread-bindings (hash-map ~@(mapcat (fn [[v init]] [(list 'var v) init]) (partition 2 bindings))))
     (try ~@body (finally (pop-thread-bindings)))))

(defn expand-assert
  "(assert x message?): nil while *assert* is off, else the throw of an
  AssertionError when x does not hold."
  ([&form &env x]
   (when *assert*
     `(when-not ~x
        ~(list 'throw (list 'new 'java.lang.AssertionError
                            `(str "Assert failed: " (pr-str '~x)))))))
  ([&form &env x message]
   (when *assert*
     `(when-not ~x
        ~(list 'throw (list 'new 'java.lang.AssertionError
                            `(str "Assert failed: " ~message "\n" (pr-str '~x))))))))

(defn expand-locking
  "(locking x body*): the body under the monitor of x."
  [&form &env x & body]
  `(let [lockee# ~x]
     (try
       (let [locklocal# lockee#]
         (monitor-enter locklocal#)
         (try ~@body (finally (monitor-exit locklocal#)))))))

(defn expand-with-open
  "(with-open [name init ...] body*): each name bound and closed after the
  body, the last first."
  [&form &env bindings & body]
  (binding-vector &form bindings)
  (cond
    (empty? bindings) `(do ~@body)
    (symbol? (nth bindings 0)) `(let ~(subvec bindings 0 2)
                              (try
                                (with-open ~(subvec bindings 2) ~@body)
                                (finally (. ~(nth bindings 0) ~'close))))
    :else (throw (IllegalArgumentException. "with-open only allows Symbols in bindings"))))

(defn expand-future
  "(future body*): future-call of a thunk over the body."
  [&form &env & body]
  `(future-call (~(with-meta 'fn* {:once true}) [] ~@body)))
