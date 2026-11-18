(ns rontolisp.internal.core-macros
  "The expansions of the clojure.core macros, built into rontolisp: what
  macroexpand-1, macroexpand and a core macro's var answer for a call of one,
  as data. A program's own calls of these macros lower directly and run none of
  this. Each expander takes the call and its environment ahead of the call's
  arguments, like the oracle's macro functions, and answers the oracle's form:
  the special forms let*, loop*, letfn*, fn* and case* under the binding
  macros, if and do under the conditionals. Written for this front end from
  the documented behaviour of the macros of the same names and diffed against
  their expansions on clj 1.12.6; destructure is clojure.core's own function."
  (:require [rontolisp.internal.types :as types]))

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

;; case. The form it expands to, as the oracle prints it:
;;   (case* value shift mask default table switch-type test-type skip-check)
;; The table maps each test's code to [test then], codes ascending. A code is
;; the test itself when every test is an int, else the test's Java hash; under
;; a nonzero mask it is that number shifted right and masked. Tests sharing a
;; hash share one entry, keyed by the hash, whose then tries each of them, and
;; skip-check holds the codes of such entries. Which shift and mask the
;; oracle picks was read off its expansions of tests chosen for the purpose:
;; none while the codes span fewer than 8192 values, else the narrowest mask
;; and for it the smallest shift that keep the codes apart.

(defn- case-pairs
  "The [test then] pairs of case's clauses, a list of tests giving a pair
  for each of them; a test written twice is refused."
  [clauses]
  (let [pairs (vec (mapcat (fn [[tests then]]
                             (map (fn [test] [test then]) (if (seq? tests) tests [tests])))
                           (partition 2 clauses)))]
    (reduce (fn [seen [test _]]
              (when (some #(= test %) seen)
                (throw (IllegalArgumentException. (str "Duplicate case test constant: " test))))
              (push seen test))
            [] pairs)
    pairs))

(defn- hashed-rows
  "The rows [code test then shared?] of tests dispatched on their hashes, in
  the order the hashes first appear. Tests sharing a hash make one row,
  marked shared: its test is the hash and its then compares value with each
  of them in turn, ending in default."
  [value default pairs]
  (let [code (fn [[test _]] (if (nil? test) 0 (.hashCode test)))]
    (map (fn [h]
           (let [sharing (filter #(= h (code %)) pairs)]
             (if (next sharing)
               [h h `(condp = ~value ~@(mapcat (fn [[test then]] [(list 'quote test) then]) sharing) ~default) true]
               [h (first (first sharing)) (second (first sharing)) false])))
         (distinct (map code pairs)))))

(defn- narrowed
  "The key a table of the given shift and mask files a code under: the code
  itself when there is no mask."
  [code shift mask]
  (if (zero? mask) code (bit-and mask (bit-shift-right code shift))))

(defn- narrowing
  "The [shift mask] for a table of the codes: [0 0] while they span fewer
  than 8192 values; else the first pair that keeps them apart, trying masks
  from one bit wide to thirteen and under each the shifts from 0 to 30; nil
  when no pair does."
  [codes]
  (if (< (- (apply max codes) (apply min codes)) 8192)
    [0 0]
    (loop [n 0]
      (when (< n (* 13 31))
        (let [shift (rem n 31)
              mask (dec (bit-shift-left 2 (quot n 31)))]
          (if (apply distinct? (map #(narrowed % shift mask) codes))
            [shift mask]
            (recur (inc n))))))))

(defn expand-case
  "(case e clause* default?): e bound to a fresh name and case* over the
  table of the clauses' tests; without clauses, the default alone. With no
  default, the one that refuses the value."
  [&form &env e & clauses]
  (let [value (gensym)
        default (if (odd? (count clauses)) (last clauses) (no-match value))
        pairs (case-pairs clauses)]
    (if (empty? pairs)
      `(let [~value ~e] ~default)
      (let [tests (map first pairs)
            ints? (every? #(and (integer? %) (<= -2147483648 % 2147483647)) tests)
            rows (if ints?
                   (map (fn [[test then]] [(int test) (int test) then false]) pairs)
                   (hashed-rows value default pairs))
            fit (narrowing (map first rows))
            [shift mask] (or fit [0 0])
            filed (sort-by first (map (fn [[code test then shared?]] [(narrowed code shift mask) [test then] shared?])
                                      rows))
            table (reduce (fn [table [k entry _]] (assoc table k entry)) {} filed)
            shared (map first (filter #(nth % 2) filed))]
        `(let [~value ~e]
           (case* ~value ~shift ~mask ~default ~table ~(if fit :compact :sparse)
                  ~@(cond ints? [:int]
                          (every? keyword? tests) [:hash-identity (when (seq shared) (set shared))]
                          :else [:hash-equiv (when (seq shared) (set shared))])))))))

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

;; doseq and for. What each expands to is the oracle's form as it prints it,
;; modifier by modifier and level by level; how the forms are assembled is
;; this file's.

(defn- doseq-forms
  "The forms that run the remaining steps of a doseq around its body and
  then carry on with onward: the recur of the walk the steps sit in, or a
  lone nil outside any walk. A collection step is one loop form with onward
  behind it; inside the loop its member is bound twice, once per way of
  walking -- through a chunk (taken under the name every level shares) and
  member by member -- each with its own recur for what is under it. A test
  takes onward inside itself, since a :when that fails still carries on and
  a :while that fails does not. A :let wraps the first form of what is
  under it and leaves the rest behind itself."
  [chunk-name steps body onward]
  (if-not (seq steps)
    (cons `(do ~@body) onward)
    (let [k (first steps)
          v (second steps)
          under (fn [onward] (doseq-forms chunk-name (nnext steps) body onward))]
      (cond
        (= k :let) (let [forms (under onward)] (cons `(let ~v ~(first forms)) (next forms)))
        (= k :when) (list `(if ~v (do ~@(under onward)) ~@onward))
        (= k :while) (list `(when ~v ~@(under onward)))
        (keyword? k) (throw (IllegalArgumentException. (str "doseq takes :let, :when and :while, not " k)))
        :else
        (let [walked (gensym "seq_")
              chunk (gensym "chunk_")
              size (gensym "count_")
              i (gensym "i_")]
          (cons `(loop [~walked (seq ~v) ~chunk nil ~size 0 ~i 0]
                   (if (< ~i ~size)
                     (let [~k (.nth ~chunk ~i)]
                       ~@(under (list `(recur ~walked ~chunk ~size (unchecked-inc ~i)))))
                     (when-let [~walked (seq ~walked)]
                       (if (chunked-seq? ~walked)
                         (let [~chunk-name (chunk-first ~walked)]
                           (recur (chunk-rest ~walked) ~chunk-name (int (count ~chunk-name)) (int 0)))
                         (let [~k (first ~walked)]
                           ~@(under (list `(recur (next ~walked) nil 0 0))))))))
                onward))))))

(defn expand-doseq
  "(doseq [step*] body*): the body run for each binding of the nested walk,
  under the :let, :when and :while steps."
  [&form &env seq-exprs & body]
  (binding-vector &form seq-exprs)
  (first (doseq-forms `c# (seq seq-exprs) body (list nil))))

(defn- for-levels
  "The levels of a for binding vector, [binding coll modifiers]: a binding
  and its collection, then the [keyword form] pairs up to the next binding.
  A modifier ahead of every binding has no level to belong to and is refused
  as the oracle refuses it."
  [seq-exprs]
  (loop [levels [] pairs (seq (partition 2 seq-exprs))]
    (if-not pairs
      levels
      (let [[bind coll] (first pairs)
            modifiers (take-while #(keyword? (first %)) (next pairs))]
        (when (keyword? bind)
          (throw (IllegalStateException. "Can't pop empty vector")))
        (recur (push levels [bind coll modifiers])
               (seq (drop (count modifiers) (next pairs))))))))

(defn- for-guarded
  "reached under a level's modifiers, the first outermost: a :let binds
  around what follows, a :while that fails ends the level, and a :when that
  fails goes to skip, the form that moves on to the next member."
  [modifiers skip reached]
  (reduce (fn [inner [k v]]
            (cond
              (= k :let) `(let ~v ~inner)
              (= k :while) `(when ~v ~inner)
              (= k :when) `(if ~v ~inner ~skip)
              :else (throw (IllegalArgumentException. (str "Invalid 'for' keyword " k)))))
          reached (reverse modifiers)))

(defn- for-innermost
  "The function of the last level, from a seq to the lazy seq of body over
  it: a chunked seq a chunk at a time, each chunk's answers gathered in a
  buffer by a loop that answers whether the level goes on; any other seq
  member by member."
  [shared [bind _ modifiers] body]
  (let [{:keys [c size]} shared
        iter (gensym "iter__")
        xs (gensym "s__")
        i (gensym "i__")
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
                          ~(for-guarded modifiers `(recur (unchecked-inc ~i))
                                        `(do (chunk-append ~b ~body) (recur (unchecked-inc ~i)))))
                        true))
                  (chunk-cons (chunk ~b) (~iter (chunk-rest ~xs)))
                  (chunk-cons (chunk ~b) nil)))
              (let [~bind (first ~xs)]
                ~(for-guarded modifiers `(recur (rest ~xs))
                              `(cons ~body (~iter (rest ~xs))))))))))))

(defn- for-outer
  "The function of a level with levels under it, given the function of the
  level below and that level's collection: for each member, the seq the
  level below makes, joined ahead of the rest of this level; a member the
  level below makes nothing of is passed over."
  [shared [bind _ modifiers] below below-coll]
  (let [{:keys [iterys fs]} shared
        iter (gensym "iter__")
        xs (gensym "s__")]
    `(fn ~iter [~xs]
       (lazy-seq
        (loop [~xs ~xs]
          (when-first [~bind ~xs]
            ~(for-guarded modifiers `(recur (rest ~xs))
                          `(let [~iterys ~below
                                 ~fs (seq (~iterys ~below-coll))]
                             (if ~fs
                               (concat ~fs (~iter (rest ~xs)))
                               (recur (rest ~xs)))))))))))

(defn expand-for
  "(for [step*] body): the lazy seq of body over the nested walk, under the
  :let, :when and :while steps. Built from the last level outwards: each
  level's function takes in the one below it."
  [&form &env seq-exprs body]
  (binding-vector &form seq-exprs)
  (let [shared {:iterys `iterys# :fs `fs# :c `c# :size `size#}
        levels (for-levels seq-exprs)
        levels (if (seq levels) levels [[nil nil nil]])
        last-level (peek levels)
        whole (first (reduce (fn [[below below-coll] level]
                               [(for-outer shared level below below-coll) (second level)])
                             [(for-innermost shared last-level body) (second last-level)]
                             (reverse (pop levels))))
        iter `iter#]
    `(let [~iter ~whole]
       (~iter ~(second (first levels))))))

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

;; reify, deftype and defrecord. Written here from the macros' documentation
;; (a type body is options, then groups: a protocol, an interface or Object
;; followed by its methods) and from what the oracle answers: the shape of the
;; special forms a program's own macro may take apart, the words of each
;; refusal, and what a record answers through each of its interfaces. A
;; record's own methods are this file's, one per method its interfaces
;; declare, each written from that method's contract.

(defn- leading-options
  "A type body's options, [[keyword value]*], and what follows them. The only
  option there is, :load-ns, is handed on to deftype*; any other is refused
  by name."
  [body]
  (loop [options [] more (seq body)]
    (if (keyword? (first more))
      (recur (push options [(first more) (second more)]) (nnext more))
      (let [unknown (remove #(= :load-ns %) (map first options))]
        (when (seq unknown)
          (throw (IllegalArgumentException.
                  (apply str "Unsupported option(s) - " (interpose " " unknown)))))
        [options more]))))

(defn- grouped
  "The groups of a type body in the order their heads first appear,
  [[head methods]*]: a head is a symbol, the lists after it are its methods,
  and a head written again starts over with the methods that follow it."
  [specs]
  (loop [groups [] more (seq specs)]
    (if-not more
      groups
      (let [head (first more)
            methods (take-while seq? (next more))
            others (remove #(= head (first %)) groups)]
        (when-not (symbol? head)
          (throw (ClassCastException.
                  (str "a group of a type body starts with a protocol, an interface or Object, not " (pr-str head)))))
        (recur (if (= (count others) (count groups))
                 (push groups [head methods])
                 (mapv (fn [group] (if (= head (first group)) [head methods] group)) groups))
               (seq (drop (count methods) (next more))))))))

(defn- implemented
  "What a group's head adds to the type's interfaces: nothing for Object, a
  protocol's interface (the :on of its var's map; the var is in reach only
  while the program lowers, so where the expansion runs the head stays as
  written), else the head itself."
  [head]
  (if (or (= 'Object head) (= 'java.lang.Object head))
    []
    (let [v (types/protocol-var head)]
      [(if (var? v) (:on @v) head)])))

(defn- plain-method
  "A method as the special forms take it, (name [symbol*] body*): each
  destructuring parameter gives way to a fresh p__ name and the body moves
  into a let taking those names apart."
  [method]
  (let [params (second method)
        body (nnext method)
        named (mapv (fn [p] (if (symbol? p) p (gensym "p__"))) params)
        taken (vec (mapcat (fn [p g] (if (symbol? p) [] [p g])) params named))]
    (if (seq taken)
      (list (first method) (with-meta named (meta params)) `(let ~taken ~@body))
      method)))

(defn- type-body
  "A type body read whole: {:options [keyword value ...], :interfaces
  [symbol*], :methods (method*)}, the methods in group order."
  [body]
  (let [[options specs] (leading-options body)
        groups (grouped specs)]
    {:options (vec (mapcat identity options))
     :interfaces (vec (mapcat (fn [group] (implemented (first group))) groups))
     :methods (map plain-method (mapcat second groups))}))

(defn expand-reify
  "(reify options* group*): reify* over the interfaces the groups name and
  their methods, with the metadata of the call."
  [&form &env & body]
  (let [{:keys [interfaces methods]} (type-body body)]
    (with-meta (list* 'reify* interfaces methods) (meta &form))))

(defn- checked-fields
  "The field vector of the type named nom, refused in the oracle's words
  when it is no vector, takes a name a record's class keeps for itself (its
  metadata, the entries beyond its fields, the two hash caches), or holds
  anything that is not a symbol."
  [fields nom]
  (when-not (vector? fields)
    (throw (AssertionError. "No fields vector given.")))
  (when (some (fn [slot] (some #(= slot %) fields)) '[__meta __extmap __hash __hasheq])
    (throw (AssertionError.
            "The names in #{__hash __meta __hasheq __extmap} cannot be used as field names for types or records.")))
  (let [strays (remove symbol? fields)]
    (when (seq strays)
      (throw (AssertionError.
              (apply str "defrecord and deftype fields must be symbols, " *ns* "." nom " had: "
                     (interpose ", " strays))))))
  fields)

(defn- naming
  "The names a type called nom goes by in the current namespace: the tag
  deftype* files it under, its class and its positional factory."
  [nom]
  {:tag (symbol (name (ns-name *ns*)) (name nom))
   :class (symbol (str (namespace-munge *ns*) "." nom))
   :factory (symbol (str "->" nom))})

(defn- positional
  "The definition of the factory taking the fields, given by their bare
  names, in order. A function has at most twenty named parameters, so the
  fields past the twentieth arrive as a rest argument, whose count is
  checked when the factory runs."
  [factory class fields]
  (let [doc (str "Positional factory function for class " class ".")
        named (vec (take 20 fields))
        extra (- (count fields) 20)]
    (if (pos? extra)
      `(defn ~factory ~doc ~(push named '& 'overage)
         (when-not (= ~extra (count ~'overage))
           (throw (clojure.lang.ArityException. (+ 20 (count ~'overage)) ~(name factory))))
         (new ~class ~@named ~@(map (fn [i] `(nth ~'overage ~i)) (range extra))))
      `(defn ~factory ~doc ~named (new ~class ~@named)))))

(defn- bare
  "The field names without the metadata their declarations carry."
  [fields]
  (mapv (fn [field] (with-meta field nil)) fields))

(defn expand-deftype
  "(deftype name [field*] options* group*): the class by deftype* over the
  fields, implementing the groups' interfaces and IType; then its import,
  its positional factory, and the class as the value."
  [&form &env nom fields & body]
  (let [fields (checked-fields fields nom)
        {:keys [options interfaces methods]} (type-body body)
        {:keys [tag class factory]} (naming nom)]
    `(let []
       ~(list* 'deftype* tag (with-meta class (meta nom)) fields
               :implements (push interfaces 'clojure.lang.IType)
               (concat options methods))
       (import ~class)
       ~(positional factory class (bare fields))
       ~class)))

;; a record's own methods

(defn- own
  "A method the record supplies, named the way a record's expansion names
  its own, clojure.core/name, so that a program rewriting the expansion can
  tell them from the methods of the body."
  [nom params & body]
  (list* (symbol "clojure.core" (name nom)) params body))

(defn- field-keys
  "The keywords a record's fields are reached by, in field order."
  [fields]
  (map (fn [field] (keyword (name field))) fields))

(defn- by-key
  "A case over the key k with one clause per field -- (clause field) answers
  its result -- and otherwise as the last form; with no fields, otherwise
  alone."
  [k fields clause otherwise]
  (if (seq fields)
    `(case ~k ~@(mapcat (fn [field key] [key (clause field)]) fields (field-keys fields)) ~otherwise)
    otherwise))

(defn- by-field?
  "A case over the key k answering field-form for the keyword of any field
  and otherwise for any other key; with no fields, otherwise alone."
  [k fields field-form otherwise]
  (if (seq fields)
    `(case ~k ~(field-keys fields) ~field-form ~otherwise)
    otherwise))

(defn- cached-hash
  "The body of a hashing method: what the slot caches, computed by the form
  and stored on the first call (a cache of zero is an empty one)."
  [slot form]
  (let [h (gensym "h__")]
    `(let [~h ~slot]
       (if (zero? ~h)
         (let [~h (int ~form)]
           (set! ~slot ~h)
           ~h)
         ~h))))

(defn- record-methods
  "The methods every record has, for the class of the given fields. Inside
  its own methods a type goes by its unqualified name, self, as deftype
  documents: the class's name resolves only once deftype* has defined it.
  The slots __meta and __extmap hold the record's metadata and its entries
  beyond the fields (nil while there are none).
  - IObj: its metadata, and a copy carrying other metadata.
  - ILookup and IKeywordLookup: a field's value by its keyword, anything
    else from __extmap; the thunk of a field's keyword reads that field off
    a record of this class and answers itself for any other target.
  - IPersistentMap: the fields in order, then the entries of __extmap; assoc
    of a field keeps the record and of another key grows __extmap; without a
    field it is a plain map, without another key still the record; conj
    takes an entry, a pair, or a collection of entries; there is no empty
    record; a record equals only a record of its class with equal fields
    and equal __extmap.
  - Object and IHashEq: equals and hashCode as the map of its entries, and
    hasheq that map's, mixed with the hash of the class's name; both hashes
    are kept in their slots.
  - java.util.Map: read only."
  [self class fields]
  (let [this (gensym "this__")
        k (gensym "k__")
        v (gensym "v__")
        other (gensym "other__")
        thunk (gensym "thunk__")
        entry (gensym "entry__")
        md '__meta
        ext '__extmap
        rebuilt (fn [fields md ext] `(new ~self ~@fields ~md ~ext))
        refusal (list 'throw (list 'java.lang.UnsupportedOperationException.))]
    (concat
     [(own 'meta [this] md)
      (own 'withMeta [this v] `(new ~self ~@fields ~v ~ext ~'__hash ~'__hasheq))
      (own 'valAt [this k] `(.valAt ~this ~k nil))
      (own 'valAt [this k v] (by-key k fields identity `(get ~ext ~k ~v)))
      (own 'getLookupThunk [this k]
           (by-field? k fields
                      `(reify clojure.lang.ILookupThunk
                         (~'get [~thunk ~other] (if (instance? ~self ~other) (get ~other ~k) ~thunk)))
                      nil))
      (own 'count [this] `(+ ~(count fields) (count ~ext)))
      (own 'seq [this]
           `(seq (concat ~(mapv (fn [field] `(clojure.lang.MapEntry/create ~(keyword (name field)) ~field)) fields)
                         ~ext)))
      (own 'iterator [this] `(clojure.lang.SeqIterator. (.seq ~this)))
      (own 'containsKey [this k] (by-field? k fields true `(contains? ~ext ~k)))
      (own 'entryAt [this k] `(when (.containsKey ~this ~k) (clojure.lang.MapEntry/create ~k (.valAt ~this ~k))))
      (own 'assoc [this k v]
           (by-key k fields
                   (fn [field] (rebuilt (map (fn [f] (if (= f field) v f)) fields) md ext))
                   (rebuilt fields md `(assoc ~ext ~k ~v))))
      (own 'without [this k]
           (by-field? k fields
                      `(dissoc (with-meta (into {} ~this) ~md) ~k)
                      (rebuilt fields md `(not-empty (dissoc ~ext ~k)))))
      (own 'cons [this v]
           `(if (vector? ~v)
              (assoc ~this (nth ~v 0) (nth ~v 1))
              (reduce (fn [~other ~entry] (assoc ~other (key ~entry) (val ~entry))) ~this ~v)))
      (own 'empty [this]
           (list 'throw (list 'java.lang.UnsupportedOperationException. (str "Can't create empty: " class))))
      (own 'equiv [this other]
           `(boolean
             (or (identical? ~this ~other)
                 (and (instance? ~self ~other)
                      ~@(map (fn [field] `(= ~field (. ~other ~(symbol (str "-" field))))) fields)
                      (= ~ext (. ~other ~'-__extmap))))))
      (own 'equals [this other] `(clojure.lang.APersistentMap/mapEquals ~this ~other))
      (own 'hashCode [this] (cached-hash '__hash `(clojure.lang.APersistentMap/mapHash ~this)))
      (own 'hasheq [this]
           (cached-hash '__hasheq `(bit-xor ~(hash class) (clojure.lang.APersistentMap/mapHasheq ~this))))
      (own 'size [this] `(.count ~this))
      (own 'isEmpty [this] `(zero? (.count ~this)))
      (own 'get [this k] `(.valAt ~this ~k))
      (own 'containsValue [this v] `(boolean (some (hash-set ~v) (vals ~this))))
      (own 'keySet [this] `(set (keys ~this)))
      (own 'values [this] `(vals ~this))
      (own 'entrySet [this] `(set ~this))]
     (map (fn [[nom arity]] (own nom (vec (take arity [this k v])) refusal))
          '[[put 3] [remove 2] [putAll 2] [clear 1]]))))

(defn expand-defrecord
  "(defrecord name [field*] options* group*): the class by deftype* over the
  fields and the slots a record keeps, implementing the groups' interfaces
  and a record's own, with the record's methods ahead of the body's; around
  it the factories declared, the class imported, ->name and map->name
  defined, and the class as the value."
  [&form &env nom fields & body]
  (let [fields (checked-fields fields nom)
        {:keys [options interfaces methods]} (type-body body)
        {:keys [tag class factory]} (naming nom)
        names (bare fields)
        from-map (symbol (str "map->" nom))
        cache (fn [slot] (with-meta slot {:unsynchronized-mutable true :tag 'int}))
        m (gensym "m__")]
    `(let []
       (declare ~factory)
       (declare ~from-map)
       ~(list* 'deftype* tag (with-meta class (meta nom))
               (push fields '__meta '__extmap (cache '__hash) (cache '__hasheq))
               :implements (push interfaces
                                 'clojure.lang.IRecord 'clojure.lang.IHashEq 'clojure.lang.IObj
                                 'clojure.lang.ILookup 'clojure.lang.IKeywordLookup
                                 'clojure.lang.IPersistentMap 'java.util.Map 'java.io.Serializable)
               (concat options (record-methods (symbol (name nom)) class names) methods))
       (import ~class)
       ~(positional factory class names)
       (defn ~from-map
         ~(str "Factory function for class " class ", taking a map of keywords to field values.")
         [~m]
         (~(symbol (str class) "create") (if (map? ~m) ~m (into {} ~m))))
       ~class)))
