(ns clojure.pprint
  "Pretty printing, built into rontolisp: pprint lays data out within the right
  margin as Clojure's pretty printer does, through a dispatch function a program
  can extend (simple-dispatch is a multimethod) or replace. Written for this front
  end from the documented behaviour of Clojure's namespace of the same name; the
  layout itself is the run-time library's (rontolisp.internal.pprint)."
  (:require [rontolisp.internal.pprint :as kernel]))

(def ^:dynamic *print-pretty*
  "Whether write prints prettily; true unless bound otherwise."
  true)

(def ^:dynamic *print-right-margin*
  "The column pretty printing keeps its lines before, 72 by default; nil lets a
  line run as long as it needs."
  72)

(def ^:dynamic *print-miser-width*
  "How close to the right margin a logical block may start before it prints in
  miser style (only miser and fill newlines read it); nil turns miser style off."
  40)

(def ^:dynamic *print-suppress-namespaces*
  "Whether a symbol prints without its namespace."
  nil)

(def ^:dynamic *print-radix*
  "Whether an integer or a ratio prints with its radix mark (#x, #o, #b, #Nr, or
  a trailing dot in base 10)."
  nil)

(def ^:dynamic *print-base*
  "The base, 2 to 36, integers and ratios print in."
  10)

(defmacro pprint-logical-block
  "Runs body as a logical block of the pretty print in progress. The leading
  options :prefix and :suffix are strings written around the body, and
  :per-line-prefix one written at the start of each of its lines. Past
  *print-level* blocks deep it writes # instead."
  [& args]
  (let [[options body] (loop [options {} args args]
                         (if (contains? #{:prefix :per-line-prefix :suffix} (first args))
                           (recur (assoc options (first args) (second args)) (next (next args)))
                           [options args]))]
    `(do (when (kernel/start ~(:prefix options) ~(:per-line-prefix options) ~(:suffix options)
                             *print-level*)
           (try ~@body (finally (kernel/end ~(:suffix options)))))
         nil)))

(defmacro print-length-loop
  "A loop like loop whose body runs at most *print-length* times, writing ...
  where it is cut short."
  [bindings & body]
  (let [n (gensym "length-count")]
    (letfn [(counted [form]
              (cond
                (and (seq? form) (= 'recur (first form)))
                (apply list 'recur (list 'clojure.core/inc n) (map counted (rest form)))
                (and (seq? form) (contains? '#{loop fn fn* letfn} (first form))) form
                (seq? form) (apply list (map counted form))
                (vector? form) (vec (map counted form))
                :else form))]
      `(loop ~(apply vector n 0 bindings)
         (if (or (not *print-length*) (< ~n *print-length*))
           (do ~@(map counted body))
           (print "..."))))))

(defn pprint-newline
  "A conditional newline of kind :linear, :miser, :fill or :mandatory in the
  logical block being printed; nothing outside a pretty print."
  [kind]
  (kernel/newline kind))

(defn pprint-indent
  "Sets the indentation of the logical block being printed to n columns past
  its start (:block) or past the current column (:current)."
  [relative-to n]
  (kernel/indent relative-to n))

(defn pprint-tab
  "Column tabulation, which Clojure's pretty printer does not implement either."
  [kind colnum colinc]
  (throw (UnsupportedOperationException. "pprint-tab is not yet implemented")))

(defn fresh-line
  "A newline unless the output is at the start of a line."
  []
  (kernel/fresh-line))

(defn get-pretty-writer
  "writer itself: pretty printing happens inside pprint and write here, whatever
  the writer."
  [writer]
  writer)

(defmulti simple-dispatch
  "The pprint dispatch for data: vectors, lists and seqs, maps and records, and
  sets lay out as logical blocks with linear newlines; anything else prints as pr
  prints it. A program adds a method for its own class."
  class)

(def ^:dynamic *print-pprint-dispatch*
  "The function pprint and write-out hand each object to; simple-dispatch unless
  bound otherwise."
  simple-dispatch)

(defn write-out
  "Writes object through *print-pprint-dispatch* inside a pretty print (as pr
  does when *print-pretty* is false), honouring *print-length*; answers whether
  the length was reached, having written ... instead."
  [object]
  (let [reached (kernel/length-reached *print-length*)]
    (cond
      (not *print-pretty*) (pr object)
      reached (print "...")
      :else (do (kernel/count-object)
                (*print-pprint-dispatch* object)))
    reached))

(defn- pprint-meta
  "With *print-meta*, x's metadata ahead of it as pr writes it."
  [x]
  (when-let [m (and *print-meta* (meta x))]
    (print "^")
    (write-out m)
    (print " ")
    (pprint-newline :linear)))

(defn- pprint-items
  "The members of the seq s in a logical block between prefix and suffix,
  separated by linear newlines."
  [prefix suffix s]
  (pprint-logical-block :prefix prefix :suffix suffix
    (print-length-loop [s s]
      (when s
        (write-out (first s))
        (when (next s)
          (print " ")
          (pprint-newline :linear)
          (recur (next s)))))))

(def ^:private reader-macros
  "The reader macro each two-member list headed by these symbols prints as."
  {'quote "'" 'var "#'" 'clojure.core/deref "@" 'clojure.core/unquote "~"})

(defn- pprint-reader-macro
  "Writes the two-member list s as the reader macro its head stands for,
  answering true; false for any other list."
  [s]
  (let [prefix (get reader-macros (first s))]
    (if (and prefix (= 2 (count s)))
      (do (print prefix) (write-out (second s)) true)
      false)))

(defn- lifted-namespace
  "The namespace every key of the map m shares, when each is a keyword or a
  symbol qualified by it and *print-namespace-maps* is on; else nil."
  [m]
  (when (and *print-namespace-maps* (seq m))
    (let [ns (first (map (fn [k] (when (or (keyword? k) (symbol? k)) (namespace k))) (keys m)))]
      (when (and ns (every? (fn [k] (and (or (keyword? k) (symbol? k)) (= ns (namespace k)))) (keys m)))
        ns))))

(defn- pprint-map
  "The entries of the map m as logical blocks of key and value, separated by a
  comma and a linear newline; a namespace all its keys share lifted to #:ns."
  [m]
  (pprint-meta m)
  (let [ns (lifted-namespace m)
        entries (or (kernel/members m) (seq m))
        entries (if ns
                  (map (fn [[k v]] [(if (keyword? k) (keyword (name k)) (symbol (name k))) v]) entries)
                  entries)]
    (pprint-logical-block :prefix (if ns (str "#:" ns "{") "{") :suffix "}"
      (print-length-loop [s entries]
        (when s
          (pprint-logical-block
            (write-out (ffirst s))
            (print " ")
            (pprint-newline :linear)
            (kernel/reset-length)
            (write-out (second (first s))))
          (when (next s)
            (print ", ")
            (pprint-newline :linear)
            (recur (next s))))))))

(defmethod simple-dispatch nil [_]
  (pr nil))

(defn- pprint-default
  "x laid out by its kind: a map, vector, set or seq as a logical block, a
  reader macro form abbreviated, anything else as pr prints it (a symbol
  without its namespace under *print-suppress-namespaces*, an integer or ratio
  in *print-base* and *print-radix*)."
  [x]
  (cond
    (map? x) (pprint-map x)
    (vector? x) (do (pprint-meta x) (pprint-items "[" "]" (seq x)))
    (set? x) (do (pprint-meta x) (pprint-items "#{" "}" (or (kernel/members x) (seq x))))
    (seq? x) (when-not (pprint-reader-macro x)
               (pprint-meta x)
               (pprint-items "(" ")" (seq x)))
    (and *print-suppress-namespaces* (symbol? x)) (print (name x))
    (and (rational? x) (or (not= *print-base* 10) *print-radix*))
    (print (kernel/number-string x *print-base* *print-radix*))
    :else (pr x)))

;; one method per class the common values have, so they dispatch at the first
;; lookup; every other value (a record, a deftype) reaches :default
(defmethod simple-dispatch clojure.lang.IPersistentVector [x] (pprint-default x))
(defmethod simple-dispatch clojure.lang.IPersistentMap [x] (pprint-default x))
(defmethod simple-dispatch clojure.lang.IPersistentSet [x] (pprint-default x))
(defmethod simple-dispatch clojure.lang.IPersistentList [x] (pprint-default x))
(defmethod simple-dispatch java.lang.Number [x] (pprint-default x))
(defmethod simple-dispatch java.lang.String [x] (pprint-default x))
(defmethod simple-dispatch clojure.lang.Keyword [x] (pprint-default x))
(defmethod simple-dispatch clojure.lang.Symbol [x] (pprint-default x))
(defmethod simple-dispatch java.lang.Boolean [x] (pprint-default x))
(defmethod simple-dispatch java.lang.Character [x] (pprint-default x))
(defmethod simple-dispatch :default [x] (pprint-default x))

;; code-dispatch: the layouts of Clojure code. A run of writes the code layouts
;; spell in sequence stops at the first object *print-length* cuts short, as
;; Clojure's own layouts (format directives) do.

(def ^:private ^:dynamic *code-symbols*
  "While the body of an anonymous function literal prints, the spelling (%, %1,
  %2, ...) of each of its parameters."
  {})

(defn- write-run
  "Writes the members of s, each but the last followed by a space and a
  newline of kind, stopping at the first one *print-length* cuts short."
  [s kind]
  (loop [s (seq s)]
    (when (and s (not (write-out (first s))) (next s))
      (print " ")
      (pprint-newline kind)
      (recur (next s)))))

(defn- code-plain
  "The list s as code with no layout of its own: its members on one line, or
  each on a line of its own indented one column past the parenthesis."
  [s]
  (pprint-logical-block :prefix "(" :suffix ")"
    (pprint-indent :block 1)
    (print-length-loop [s (seq s)]
      (when s
        (write-out (first s))
        (when (next s)
          (print " ")
          (pprint-newline :linear)
          (recur (next s)))))))

(defn- code-pairs
  "The members of s two by two, each pair a block whose second member may go
  below its first in miser style, the pairs separated by linear newlines."
  [s]
  (print-length-loop [s (seq s)]
    (when s
      (pprint-logical-block
        (write-out (first s))
        (when (next s)
          (print " ")
          (pprint-newline :miser)
          (write-out (second s))))
      (when (next (rest s))
        (print " ")
        (pprint-newline :linear)
        (recur (next (rest s)))))))

(defn- code-head
  "Writes the head of a defining form and, past a space and a block indent of
  one, the name after it (which goes below in miser style)."
  [head named]
  (when-not (write-out head)
    (print " ")
    (pprint-indent :block 1)
    (pprint-newline :miser)
    (write-out named)))

(defn- code-hold-first
  "def, ->, . and the like: the first operand stays beside the head unless in
  miser style, the rest go below it."
  [s]
  (pprint-logical-block :prefix "(" :suffix ")"
    (when-not (write-out (first s))
      (when-let [more (next s)]
        (print " ")
        (pprint-newline :miser)
        (when-not (write-out (first more))
          (when-let [more (next more)]
            (print " ")
            (pprint-newline :linear)
            (write-run more :linear)))))))

(defn- code-defn
  "defn, defmacro, fn: the name beside the head, then the docstring, the
  attribute map and the parameter vector and body, or the arities."
  [s]
  (if (next s)
    (let [[head named & more] s
          [doc more] (if (string? (first more)) [(first more) (next more)] [nil more])
          [attrs more] (if (map? (first more)) [(first more) (next more)] [nil more])]
      (pprint-logical-block :prefix "(" :suffix ")"
        (code-head head named)
        (when doc
          (print " ")
          (pprint-newline :linear)
          (write-out doc))
        (when attrs
          (print " ")
          (pprint-newline :linear)
          (write-out attrs))
        (when (seq more)
          (print " ")
          (pprint-newline (if (and (vector? (first more)) (not (or doc attrs))) :miser :linear))
          (write-run more :linear))))
    (code-plain s)))

(defn- code-bindings
  "A binding vector: its name and value pairs."
  [v]
  (pprint-logical-block :prefix "[" :suffix "]"
    (code-pairs v)))

(defn- code-let
  "let, loop, binding, doseq and the like: the binding vector beside the head,
  the body below."
  [s]
  (pprint-logical-block :prefix "(" :suffix ")"
    (if (and (next s) (vector? (second s)))
      (do
        (when-not (write-out (first s))
          (print " ")
          (pprint-indent :block 1)
          (pprint-newline :miser))
        (code-bindings (second s))
        (print " ")
        (pprint-newline :linear)
        (write-run (next (next s)) :linear))
      (code-plain s))))

(defn- code-if
  "if, when and their negations: the test beside the head unless in miser
  style, the branches or body below."
  [s]
  (pprint-logical-block :prefix "(" :suffix ")"
    (pprint-indent :block 1)
    (when-not (write-out (first s))
      (when-let [more (next s)]
        (print " ")
        (pprint-newline :miser)
        (when-not (write-out (first more))
          (loop [more (next more)]
            (when more
              (print " ")
              (pprint-newline :linear)
              (when-not (write-out (first more))
                (recur (next more))))))))))

(defn- code-cond
  "cond: the test and expression pairs below the head."
  [s]
  (pprint-logical-block :prefix "(" :suffix ")"
    (pprint-indent :block 1)
    (write-out (first s))
    (when (next s)
      (print " ")
      (pprint-newline :linear)
      (code-pairs (next s)))))

(defn- code-condp
  "condp: the predicate and the expression beside the head, the clause pairs
  below."
  [s]
  (if (> (count s) 3)
    (pprint-logical-block :prefix "(" :suffix ")"
      (pprint-indent :block 1)
      (when-not (write-out (first s))
        (print " ")
        (pprint-newline :miser)
        (when-not (write-out (second s))
          (print " ")
          (pprint-newline :miser)
          (when-not (write-out (nth s 2))
            (print " ")
            (pprint-newline :linear))))
      (code-pairs (drop 3 s)))
    (code-plain s)))

(defn- code-anonymous
  "fn* over a parameter vector as the literal #(...) the reader reads into it,
  each parameter spelled % (the only one) or %1, %2, ... by position."
  [s]
  (let [params (second s)
        body (first (rest (rest s)))]
    (if (vector? params)
      (binding [*code-symbols* (if (= 1 (count params))
                                 {(first params) "%"}
                                 (into {} (map (fn [p i] [p (str "%" i)])
                                               params (range 1 (inc (count params))))))]
        (pprint-logical-block :prefix "#(" :suffix ")"
          (write-run body :linear)))
      (code-plain s))))

(defn- code-reference-part
  "A list or vector inside an ns reference. A libspec of a name, a keyword and
  a value ([lib :as alias], [lib :refer [a b]]) stays on one line, a list or
  vector value filled; any other has its members after the first aligned past
  it and filled."
  [part]
  (let [open (if (vector? part) "[" "(")
        close (if (vector? part) "]" ")")]
    (if (and (= 3 (count part)) (keyword? (second part)))
      (let [[lib option value] part]
        (pprint-logical-block :prefix open :suffix close
          (when-not (write-out lib)
            (print " ")
            (when-not (write-out option)
              (print " ")))
          (if (sequential? value)
            (pprint-logical-block :prefix (if (vector? value) "[" "(") :suffix (if (vector? value) "]" ")")
              (write-run value :fill))
            (write-out value))))
      (do
        (when (empty? part)
          (throw (Exception. "Not enough arguments for format definition")))
        (pprint-logical-block :prefix open :suffix close
          (when-not (write-out (first part))
            (print " ")
            (pprint-indent :current 0)
            (write-run (rest part) :fill)))))))

(defn- code-reference
  "One reference of an ns form, (:require ...) and the like: its arguments
  aligned one column past the keyword, a line ending after a list or vector
  argument when they do not fit, filled after any other."
  [reference]
  (if (sequential? reference)
    (pprint-logical-block :prefix (if (vector? reference) "[" "(")
                          :suffix (if (vector? reference) "]" ")")
      (when-not (write-out (first reference))
        (pprint-indent :current 0))
      (loop [args (next reference)]
        (when args
          (print " ")
          (let [arg (first args)]
            (if (sequential? arg)
              (code-reference-part arg)
              (write-out arg))
            (when (next args)
              (pprint-newline (if (sequential? arg) :linear :fill))))
          (recur (next args)))))
    (write-out reference)))

(defn- code-ns
  "ns: the name beside the head, then the docstring, the attribute map and
  each reference on lines of their own."
  [s]
  (if (next s)
    (let [[head named & more] s
          [doc more] (if (string? (first more)) [(first more) (next more)] [nil more])
          [attrs references] (if (map? (first more)) [(first more) (next more)] [nil more])]
      (pprint-logical-block :prefix "(" :suffix ")"
        (code-head head named)
        (when (or doc attrs (seq references))
          (pprint-newline :mandatory))
        (when doc
          (print (str "\"" doc "\""))
          (when (or attrs (seq references))
            (pprint-newline :mandatory)))
        (when attrs
          (when (and (not (write-out attrs)) (seq references))
            (pprint-newline :mandatory)))
        (loop [references (seq references)]
          (when references
            (code-reference (first references))
            (when (next references)
              (pprint-newline :linear)
              (recur (next references)))))))
    (code-plain s)))

(def ^:private code-layouts
  "The layout of each head symbol code-dispatch lays out its own way, the
  clojure.core macros and functions under their qualified names too."
  (let [special {'def code-hold-first
                 'if code-if
                 'fn* code-anonymous
                 '. code-hold-first}
        core {'defonce code-hold-first
              'defn code-defn
              'defn- code-defn
              'defmacro code-defn
              'fn code-defn
              'let code-let
              'loop code-let
              'binding code-let
              'with-local-vars code-let
              'with-open code-let
              'when-let code-let
              'if-let code-let
              'doseq code-let
              'dotimes code-let
              'when-first code-let
              'if-not code-if
              'when code-if
              'when-not code-if
              'cond code-cond
              'condp code-condp
              '.. code-hold-first
              '-> code-hold-first
              'locking code-hold-first
              'struct code-hold-first
              'struct-map code-hold-first
              'ns code-ns}]
    (merge special core
           (into {} (map (fn [[sym layout]] [(symbol "clojure.core" (name sym)) layout]) core)))))

(defn- code-list
  "The list s as code: a reader macro form abbreviated, a form whose head has
  a layout of its own laid out so, any other as a plain list."
  [s]
  (when-not (pprint-reader-macro s)
    (if-let [layout (get code-layouts (first s))]
      (layout s)
      (code-plain s))))

(defn- code-symbol
  "A symbol in code: an anonymous function's parameter as its % spelling."
  [sym]
  (if-let [spelling (get *code-symbols* sym)]
    (print spelling)
    (if *print-suppress-namespaces*
      (print (name sym))
      (pr sym))))

(defn- code-default
  "x laid out as code: a seq as a list, a symbol as code-symbol does, anything
  else as simple-dispatch lays it out."
  [x]
  (cond
    (seq? x) (code-list x)
    (symbol? x) (code-symbol x)
    :else (pprint-default x)))

(defmulti code-dispatch
  "The pprint dispatch for Clojure code: def, defn, let, if, cond, condp, ns,
  anonymous function literals and the like each in the layout Clojure's
  pretty printer gives them, other lists as plain calls, and data as
  simple-dispatch lays it out. A program adds a method for its own class."
  class)

(defmethod code-dispatch clojure.lang.IPersistentList [x] (code-default x))
(defmethod code-dispatch clojure.lang.Symbol [x] (code-symbol x))
(defmethod code-dispatch clojure.lang.IPersistentVector [x] (pprint-default x))
(defmethod code-dispatch clojure.lang.IPersistentMap [x] (pprint-default x))
(defmethod code-dispatch clojure.lang.IPersistentSet [x] (pprint-default x))
(defmethod code-dispatch java.lang.Number [x] (pprint-default x))
(defmethod code-dispatch java.lang.String [x] (pprint-default x))
(defmethod code-dispatch clojure.lang.Keyword [x] (pprint-default x))
(defmethod code-dispatch java.lang.Boolean [x] (pprint-default x))
(defmethod code-dispatch java.lang.Character [x] (pprint-default x))
(defmethod code-dispatch nil [_] (pr nil))
(defmethod code-dispatch :default [x] (code-default x))

(defn pprint
  "Pretty prints object to writer (*out* when absent) within
  *print-right-margin*, then a newline."
  ([object] (pprint object *out*))
  ([object writer]
   (binding [*out* writer]
     (binding [*print-pretty* true]
       (kernel/call (fn [] (write-out object)) *print-right-margin* *print-miser-width*))
     (newline))))

(defmacro pp
  "Pretty prints the last value the REPL answered, *1."
  []
  `(pprint *1))

(defn write
  "Writes object as the options say: :stream (a writer, true for *out*, the
  default, or nil to answer the text), :pretty, :right-margin, :miser-width,
  :dispatch, :length, :level, :readably, :suppress-namespaces, :base and :radix
  (:circle and :lines are accepted and ignored). Answers the text for a nil
  :stream, else nil."
  [object & kw-args]
  (let [options (apply hash-map kw-args)
        stream (get options :stream true)
        written (fn []
                  (binding [*print-pretty* (get options :pretty *print-pretty*)
                            *print-right-margin* (get options :right-margin *print-right-margin*)
                            *print-miser-width* (get options :miser-width *print-miser-width*)
                            *print-pprint-dispatch* (get options :dispatch *print-pprint-dispatch*)
                            *print-suppress-namespaces* (get options :suppress-namespaces
                                                             *print-suppress-namespaces*)
                            *print-base* (get options :base *print-base*)
                            *print-radix* (get options :radix *print-radix*)
                            *print-length* (get options :length *print-length*)
                            *print-level* (get options :level *print-level*)
                            *print-readably* (get options :readably *print-readably*)]
                    (if *print-pretty*
                      (kernel/call (fn [] (write-out object)) *print-right-margin* *print-miser-width*)
                      (pr object))))]
    (cond
      (nil? stream) (with-out-str (written))
      (true? stream) (do (written) nil)
      :else (do (binding [*out* stream] (written)) nil))))

(defmacro with-pprint-dispatch
  "Runs body with function as *print-pprint-dispatch*."
  [function & body]
  `(binding [*print-pprint-dispatch* ~function] ~@body))

(defn set-pprint-dispatch
  "Makes function the pprint dispatch from now on."
  [function]
  (def *print-pprint-dispatch* function)
  nil)

(defn print-table
  "Prints the maps of rows as a table, one column per key of ks (the first row's
  keys when absent), each value right-aligned under its key as str spells them.
  Prints nothing for no rows."
  ([rows] (print-table (map first (or (kernel/members (first rows)) (seq (first rows)))) rows))
  ([ks rows]
   (when (seq rows)
     (let [widths (map (fn [k] (apply max (count (str k)) (map (fn [row] (count (str (get row k)))) rows))) ks)
           padded (fn [text width] (str (apply str (repeat (- width (count text)) " ")) text))
           line (fn [lead separator tail cells] (str lead (apply str (interpose separator cells)) tail))]
       (println)
       (println (line "| " " | " " |" (map (fn [k w] (padded (str k) w)) ks widths)))
       (println (line "|-" "-+-" "-|" (map (fn [w] (apply str (repeat w "-"))) widths)))
       (doseq [row rows]
         (println (line "| " " | " " |" (map (fn [k w] (padded (str (get row k)) w)) ks widths))))))))
