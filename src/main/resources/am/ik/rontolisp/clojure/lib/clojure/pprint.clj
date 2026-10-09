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

(def ^:private ^:dynamic *radix-pr*
  "Whether the dispatch writes an integer or a ratio in *print-base* and
  *print-radix*: set by pprint and write when either is not its default (as
  Clojure's rebind pr there), so cl-format's ~W writes one as pr does."
  nil)

(defn- pr-radix
  "Writes x as pr does, an integer or a ratio in *print-base* and
  *print-radix* inside a pprint or write that set them."
  [x]
  (if-let [s (and *radix-pr* (kernel/number-string x *print-base* *print-radix*))]
    (print s)
    (pr x)))

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
  "A newline unless the pretty print in progress is at the start of a line;
  outside a pretty print, which keeps no column, always a newline."
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
  in *print-base* and *print-radix* inside a pprint or write that set them)."
  [x]
  (cond
    (map? x) (pprint-map x)
    (vector? x) (do (pprint-meta x) (pprint-items "[" "]" (seq x)))
    (set? x) (do (pprint-meta x) (pprint-items "#{" "}" (or (kernel/members x) (seq x))))
    (seq? x) (when-not (pprint-reader-macro x)
               (pprint-meta x)
               (pprint-items "(" ")" (seq x)))
    (and *print-suppress-namespaces* (symbol? x)) (print (name x))
    :else (pr-radix x)))

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
     (binding [*print-pretty* true
               *radix-pr* (or *radix-pr* (not= *print-base* 10) *print-radix*)]
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
                    (binding [*radix-pr* (or *radix-pr* (not= *print-base* 10) *print-radix*)]
                      (if *print-pretty*
                        (kernel/call (fn [] (write-out object)) *print-right-margin* *print-miser-width*)
                        (pr-radix object)))))]
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

;; cl-format: Common Lisp's format directives over Clojure values. A control
;; string compiles to a format, a vector of nodes: a string of literal text,
;; or a directive [char at colon params dynamic clauses else at-least-once
;; else-separator offset], params in its definition's order with the defaults
;; filled in (:v and :# for the two read from the arguments). The executor
;; walks a format over an argument navigator [seq rest position dropped] and
;; answers the navigator moved on, or (exit navigator) when a ~^ (or a ~W cut
;; by *print-length*) ended the run. The text of the number directives is the
;; run-time library's (rontolisp.internal.pprint); the directives of the
;; pretty printer add to the pretty print in progress.

(defn- format-error
  "Throws the RuntimeException of the malformed control string s: message, the
  string, and a caret under offset."
  [s message offset]
  (throw (RuntimeException. (str message "\n" s "\n" (kernel/padding offset \space) "^\n"))))

(defn- directive-spec
  "What the directive named by the upper-case character c takes: :params,
  each [name default kind] with kind :int or :char; :flags, the flags it
  allows (:both for : and @ together); :pretty when it needs a pretty
  writer. nil for no directive."
  [c]
  (let [all #{:at :colon :both}
        padding [["mincol" 0 :int] ["colinc" 1 :int] ["minpad" 0 :int] ["padchar" \space :char]]
        integer [["mincol" 0 :int] ["padchar" \space :char] ["commachar" \, :char]
                 ["commainterval" 3 :int]]
        exponential [["w" nil :int] ["d" nil :int] ["e" nil :int] ["k" 1 :int]
                     ["overflowchar" nil :char] ["padchar" \space :char] ["exponentchar" nil :char]]]
    (case c
      (\A \S) {:params padding :flags all}
      (\D \B \O \X) {:params integer :flags all}
      \R {:params (into [["base" nil :int]] integer) :flags all}
      \P {:params [] :flags all}
      \C {:params [["char-format" nil :char]] :flags all}
      \F {:params [["w" nil :int] ["d" nil :int] ["k" 0 :int] ["overflowchar" nil :char]
                   ["padchar" \space :char]]
          :flags #{:at}}
      (\E \G) {:params exponential :flags #{:at}}
      \$ {:params [["d" 2 :int] ["n" 1 :int] ["w" 0 :int] ["padchar" \space :char]] :flags all}
      (\% \|) {:params [["count" 1 :int]] :flags #{}}
      \& {:params [["count" 1 :int]] :flags #{} :pretty true}
      \~ {:params [["n" 1 :int]] :flags #{}}
      \newline {:params [] :flags #{:at :colon}}
      \T {:params [["colnum" 1 :int] ["colinc" 1 :int]] :flags #{:at} :pretty true}
      \* {:params [["n" nil :int]] :flags #{:at :colon}}
      \? {:params [] :flags #{:at}}
      \( {:params [] :flags all}
      \[ {:params [["selector" nil :int]] :flags #{:at :colon}}
      \; {:params [["min-remaining" nil :int] ["max-columns" nil :int]] :flags #{:colon}}
      \{ {:params [["max-iterations" nil :int]] :flags all}
      \< {:params padding :flags all :pretty true}
      (\) \]) {:params [] :flags #{}}
      (\} \>) {:params [] :flags #{:colon}}
      \^ {:params [["arg1" nil :int] ["arg2" nil :int] ["arg3" nil :int]] :flags #{:colon}}
      \W {:params [] :flags all :pretty true}
      \_ {:params [] :flags all}
      \I {:params [["n" 0 :int]] :flags #{:colon}}
      nil)))

(defn- bracket
  "The closing character of the bracket directive c, whether ~; separates its
  clauses, and where its ~:; may stand (:first or :last); nil for any other."
  [c]
  (case c
    \( [\) false nil]
    \[ [\] true :last]
    \{ [\} false nil]
    \< [\> true :first]
    nil))

(defn- digit?
  "Whether c is an ASCII digit."
  [c]
  (and (char? c) (>= (int c) 48) (<= (int c) 57)))

(defn- param-token
  "The parameter token at index i of s, as [token end]: :v, :#, a character
  after a quote, the text of an integer, or nil for the empty one a comma
  follows; nil when none stands there."
  [s i]
  (let [n (count s)
        c (when (< i n) (nth s i))]
    (cond
      (nil? c) nil
      (or (= c \v) (= c \V)) [:v (inc i)]
      (= c \#) [:# (inc i)]
      (and (= c \') (< (inc i) n)
           (not (contains? #{10 13 133 8232 8233} (int (nth s (inc i))))))
      [(nth s (inc i)) (+ i 2)]
      (or (digit? c) (and (contains? #{\+ \-} c) (digit? (when (< (inc i) n) (nth s (inc i))))))
      (let [end (loop [j (inc i)] (if (digit? (when (< j n) (nth s j))) (recur (inc j)) j))]
        [(subs s i end) end])
      (= c \,) [nil i]
      :else nil)))

(defn- param-value
  "The value of a parameter token: an integer's text read as Integer/parseInt
  reads it, any other token itself."
  [token]
  (if (string? token)
    (let [magnitude (reduce (fn [acc d] (+ (* acc 10) (- (int d) 48)))
                            0 (if (digit? (first token)) token (subs token 1)))
          value (if (= (first token) \-) (- magnitude) magnitude)]
      (if (or (> value 2147483647) (< value -2147483648))
        (throw (NumberFormatException. (str "For input string: \"" token "\"")))
        value))
    token))

(defn- check-flags
  "Refuses the flags of a directive of the control string s whose definition
  does not allow them."
  [s c spec flags]
  (let [allowed (:flags spec)
        at (get flags :at)
        colon (get flags :colon)]
    (when (and at (not (contains? allowed :at)))
      (format-error s (str "\"@\" is an illegal flag for format directive \"" c "\"") at))
    (when (and colon (not (contains? allowed :colon)))
      (format-error s (str "\":\" is an illegal flag for format directive \"" c "\"") colon))
    (when (and at colon (not (contains? allowed :both)))
      (format-error s (str "Cannot combine \"@\" and \":\" flags for format directive \"" c "\"")
                    (min at colon)))))

(defn- directive-params
  "The parameters of a directive of the control string s given as [token
  offset] pairs, read, checked against its definition, and with the defaults
  filled in."
  [s c spec given]
  (let [defs (:params spec)
        given (mapv (fn [[token offset]] [(param-value token) offset]) given)]
    (when (> (count given) (count defs))
      (format-error s (str "Too many parameters for directive \"" c "\": " (count given)
                         (if (= 1 (count given)) " was" " were") " specified but only "
                         (count defs) (if (= 1 (count defs)) " is" " are") " allowed")
                    (second (first given))))
    (doseq [[[value offset] [name _ kind]] (map vector given defs)]
      (when-not (or (nil? value) (keyword? value)
                    (if (= kind :int) (integer? value) (char? value)))
        (format-error s (str "Parameter " name " has bad type in directive \"" c "\": class "
                           (if (char? value) "java.lang.Character" "java.lang.Integer"))
                      offset)))
    (vec (map-indexed (fn [i [_ default _]]
                        (let [value (first (get given i))]
                          (if (nil? value) default value)))
                      defs))))

(defn- compile-directive
  "The directive whose text starts at index i of the control string s, just
  past its tilde, and the index past it (and past the blanks a ~ newline
  swallows)."
  [s i]
  (let [n (count s)
        [given i] (loop [i i given [] comma false]
                    (if-let [[value end] (param-token s i)]
                      (let [given (conj given [value i])]
                        (when (>= end n)
                          (throw (StringIndexOutOfBoundsException. "Index 0 out of bounds for length 0")))
                        (if (= \, (nth s end))
                          (recur (inc end) given true)
                          [given end]))
                      (if comma
                        (format-error s "Badly formed parameters in format directive" i)
                        [given i])))
        [flags i] (loop [i i flags {}]
                    (let [c (when (< i n) (nth s i))
                          flag (case c \: :colon \@ :at nil)]
                      (cond
                        (nil? flag) [flags i]
                        (contains? flags flag)
                        (format-error s (str "Flag \"" c "\" appears more than once in a directive") i)
                        :else (recur (inc i) (assoc flags flag i)))))
        _ (when (>= i n)
            (throw (NullPointerException.
                    "Cannot invoke \"java.lang.Character.charValue()\" because \"directive\" is null")))
        c (nth s i)
        upper (if (and (>= (int c) 97) (<= (int c) 122)) (char (- (int c) 32)) c)
        spec (directive-spec upper)
        _ (when-not spec (format-error s (str "Directive \"" c "\" is undefined") i))
        _ (check-flags s upper spec flags)
        params (directive-params s upper spec given)
        at (contains? flags :at)
        colon (contains? flags :colon)
        end (inc i)
        end (if (and (= upper \newline) (not colon))
              (loop [j end] (if (and (< j n) (contains? #{\space \tab} (nth s j))) (recur (inc j)) j))
              end)]
    [[upper at colon params (boolean (some keyword? params)) nil nil false nil i] end]))

(defn- flat-nodes
  "The control string s as a flat vector of literal strings and directives."
  [s]
  (let [n (count s)]
    (loop [i 0 nodes []]
      (if (>= i n)
        nodes
        (let [tilde (clojure.string/index-of s "~" i)]
          (cond
            (nil? tilde) (conj nodes (subs s i))
            (> tilde i) (recur tilde (conj nodes (subs s i tilde)))
            :else (let [[node end] (compile-directive s (inc i))]
                    (recur end (conj nodes node)))))))))

(defn- opener?
  "Whether node is a directive that opens a bracket."
  [node]
  (and (vector? node) (some? (bracket (nth node 0)))))

(defn- directive?
  "Whether node is the directive c."
  [node c]
  (and (vector? node) (= c (nth node 0))))

(declare nest-clauses)

(defn- nest
  "The flat nodes of the control string s as a format, each bracket directive
  holding the clauses up to its closing directive."
  [s nodes]
  (loop [nodes (seq nodes) out []]
    (if nodes
      (let [node (first nodes)]
        (if (opener? node)
          (let [[node more] (nest-clauses s node (next nodes))]
            (recur more (conj out node)))
          (recur (next nodes) (conj out node))))
      out)))

(defn- nest-clauses
  "The bracket directive opener of the control string s holding its clauses
  taken from nodes, and the nodes past its closing directive."
  [s opener nodes]
  (let [[right separates else-at] (bracket (nth opener 0))
        offset (nth opener 9)]
    (loop [nodes nodes clause [] clauses [] else nil separator nil saw-else false]
      (if-not nodes
        (format-error s "No closing bracket found." offset)
        (let [node (first nodes)]
          (cond
            (opener? node)
            (let [[sub more] (nest-clauses s node (next nodes))]
              (recur more (conj clause sub) clauses else separator saw-else))

            (directive? node right)
            [(-> opener
                 (assoc 5 (if saw-else clauses (conj clauses clause)))
                 (assoc 6 (if saw-else [clause] else))
                 (assoc 7 (nth node 2))
                 (assoc 8 separator))
             (next nodes)]

            (and (directive? node \;) (nth node 2))
            (cond
              else (format-error s "Two else clauses (\"~:;\") inside bracket construction." offset)
              (nil? else-at)
              (format-error s "An else clause (\"~:;\") is in a bracket type that doesn't support it." offset)
              (and (= else-at :first) (seq clauses))
              (format-error
               s "The else clause (\"~:;\") is only allowed in the first position for this directive."
               offset)
              (= else-at :first) (recur (next nodes) [] clauses [clause] node false)
              :else (recur (next nodes) [] (conj clauses clause) else separator true))

            (directive? node \;)
            (cond
              saw-else
              (format-error
               s "A plain clause (with \"~;\") follows an else clause (\"~:;\") inside bracket construction."
               offset)
              (not separates)
              (format-error s "A separator (\"~;\") is in a bracket type that doesn't support it." offset)
              :else (recur (next nodes) [] (conj clauses clause) else separator false))

            :else (recur (next nodes) (conj clause node) clauses else separator saw-else)))))))

(defn- compile-nodes
  "The control string s compiled to a format."
  [s]
  (nest s (flat-nodes s)))

(defn- compile-format
  "The control string s compiled, as cl-format and formatter take it in its
  place."
  [s]
  [::compiled (compile-nodes s)])

(defn- as-format
  "The format format-in stands for: a control string compiled, a compiled one
  unwrapped; anything else (foreign format-in), which the oracle walks as a
  seq of compiled directives only when it runs."
  [format-in]
  (cond
    (string? format-in) (compile-nodes format-in)
    (and (vector? format-in) (= ::compiled (nth format-in 0 nil))) (nth format-in 1)
    :else (list ::foreign format-in)))

(def ^:private format-cache
  "The formats compiled so far, by control string: a compile is a pure
  function of the string, so cl-format's own compiles are kept too (the
  oracle's compiles on every call); emptied when it holds 512, so control
  strings built at run time cannot grow it without bound."
  (atom {}))

(defn- cached-format
  "format-in as a format, a control string compiled once."
  [format-in]
  (if (string? format-in)
    (or (get @format-cache format-in)
        (let [compiled (compile-nodes format-in)]
          (swap! format-cache (fn [cache]
                                (assoc (if (< (count cache) 512) cache {}) format-in compiled)))
          compiled))
    (as-format format-in)))

(defn- needs-pretty?
  "Whether the format holds, at any depth, a directive that writes through a
  pretty writer: ~&, ~T, ~< or ~W."
  [fmt]
  (some (fn [node]
          (and (vector? node)
               (or (contains? #{\& \T \< \W} (nth node 0))
                   (some needs-pretty? (nth node 5))
                   (some needs-pretty? (nth node 6)))))
        fmt))

;; the argument navigator

(defn- init-nav
  "A navigator over the arguments s, at the first."
  [s]
  (let [s (seq s)]
    [s s 0 false]))

(defn- next-arg
  "The next argument and the navigator past it. Past the end it is the
  oracle's Exception, unless the navigator was just repositioned: there one
  nil comes back first, as from the seq drop answers."
  [nav]
  (let [rest-args (nth nav 1)]
    (if (or rest-args (nth nav 3))
      [(first rest-args) [(nth nav 0) (next rest-args) (inc (nth nav 2)) false]]
      (throw (Exception. "Not enough arguments for format definition")))))

(defn- next-arg-or-nil
  "The next argument and the navigator past it, nil and the navigator at the
  end."
  [nav]
  (if (or (nth nav 1) (nth nav 3))
    (next-arg nav)
    [nil nav]))

(declare reposition-absolute)

(defn- reposition-relative
  "The navigator n arguments on (back for a negative n)."
  [nav n]
  (let [position (+ (nth nav 2) n)]
    (if (neg? n)
      (reposition-absolute nav position)
      [(nth nav 0) (drop n (nth nav 1)) position true])))

(defn- reposition-absolute
  "The navigator at argument position."
  [nav position]
  (if (>= position (nth nav 2))
    (reposition-relative nav (- position (nth nav 2)))
    [(nth nav 0) (drop position (nth nav 0)) position true]))

(defn- realize-params
  "The parameters of the directive node, those read from the arguments (v and
  #) realized, last first as the oracle realizes them, and the navigator
  past what they took."
  [params nav]
  (loop [i (dec (count params)) params params nav nav]
    (if (neg? i)
      [params nav]
      (let [p (nth params i)]
        (cond
          (= p :v) (let [[value nav] (next-arg nav)]
                     (recur (dec i) (assoc params i value) nav))
          (= p :#) (recur (dec i) (assoc params i (count (nth nav 1))) nav)
          :else (recur (dec i) params nav))))))

;; output: every piece a directive writes goes through emit, so a ~(...~)
;; clause converts each the way Clojure's case-converting writers do. While a
;; clause runs, (kernel/case-out) is the function each piece (a string or a
;; character) goes through: its case conversion, writing on to the output of
;; the enclosing one; nil outside one.

(defn- emit
  "Writes x, a string or a character, through the case conversion in effect."
  [x]
  (if-let [w (kernel/case-out)]
    (w x)
    (print x)))

(defn- not-pretty
  "The oracle's ClassCastException of a pretty printing directive whose *out*
  is no pretty writer."
  []
  (throw (ClassCastException. "*out* is not a pretty writer")))

(declare execute)

(defn- abort?
  "Whether the result of a run is a ~^ exit rather than a navigator."
  [result]
  (seq? result))

(defn- ascii-text
  "x as ~A (print) or ~S (readably, pr) spells it: an integer or ratio in
  *print-base* and *print-radix*."
  [x readably]
  (or (kernel/number-string x *print-base* *print-radix*)
      (if readably (pr-str x) (print-str x))))

(defn- divided
  "(quot n d), a zero d the oracle's ArithmeticException."
  [n d]
  (if (= d 0)
    (throw (ArithmeticException. "Divide by zero"))
    (quot n d)))

(defn- ascii
  "~mincol,colinc,minpad,padcharA and ~S: x padded to mincol columns, minpad
  copies at least, then colinc at a time; on the left with @."
  [node params nav readably]
  (let [[arg nav] (next-arg nav)
        [mincol colinc minpad padchar] params
        text (ascii-text arg readably)
        least (+ (count text) minpad)
        width (if (>= least mincol)
                least
                (+ least (* (inc (divided (- mincol least 1) colinc)) colinc)))
        pad (kernel/padding (- width (count text)) padchar)]
    (emit (if (nth node 1) (str pad text) (str text pad)))
    nav))

(defn- integer-arg
  "~D, ~B, ~O, ~X and ~R with a radix: the argument in base."
  [node nav base [mincol padchar commachar interval]]
  (let [[arg nav] (next-arg nav)]
    (emit (kernel/integer-text arg base (nth node 2) (nth node 1) mincol padchar commachar interval
                               *print-base* *print-radix*))
    nav))

(defn- radix
  "~R: in the radix its first parameter gives, else in English words (~:R
  ordinal) or Roman numerals (~@R, ~:@R the old style)."
  [node params nav]
  (if (some? (nth (nth node 3) 0))
    (integer-arg node nav (nth params 0) (subvec params 1))
    (let [[arg nav] (next-arg nav)
          at (nth node 1)
          colon (nth node 2)]
      (emit (if at
              (kernel/roman arg colon *print-base* *print-radix*)
              (kernel/english arg colon *print-base* *print-radix*)))
      nav)))

(defn- plural
  "~P: s unless the argument is 1 (~@P y or ies); ~:P backs up to the
  argument before."
  [node nav]
  (let [nav (if (nth node 2) (reposition-relative nav -1) nav)
        [arg nav] (next-arg nav)]
    (emit (if (= arg 1)
            (if (nth node 1) "y" "")
            (if (nth node 1) "ies" "s")))
    nav))

(defn- emit-pr
  "Writes x as pr does, a string or a character in the writes pr makes of it,
  so a ~(...~) clause converts them one by one."
  [x]
  (cond
    (nil? (kernel/case-out)) (pr x)
    (string? x) (do (emit \")
                    (doseq [c x]
                      (case c
                        \newline (emit "\\n")
                        \tab (emit "\\t")
                        \return (emit "\\r")
                        \" (emit "\\\"")
                        \\ (emit "\\\\")
                        \formfeed (emit "\\f")
                        \backspace (emit "\\b")
                        (emit c)))
                    (emit \"))
    (char? x) (do (emit \\)
                  (case x
                    \newline (emit "newline")
                    \space (emit "space")
                    \tab (emit "tab")
                    \backspace (emit "backspace")
                    \formfeed (emit "formfeed")
                    \return (emit "return")
                    (emit x)))
    :else (emit (pr-str x))))

(defn- character
  "~C: the character; ~:C its name (Space, Control-A, Meta-...); ~@C as pr
  writes it (~'o@C and ~'u@C as an octal or unicode escape)."
  [node params nav]
  (let [[c nav] (next-arg nav)]
    (cond
      (nth node 2)
      (let [code (int c)
            low (mod code 128)]
        (when (>= (mod code 256) 128) (emit "Meta-"))
        (case low
          8 (emit "Backspace")
          9 (emit "Tab")
          10 (emit "Newline")
          13 (emit "Return")
          32 (emit "Space")
          (cond
            (< low 32) (emit (str "Control-" (char (+ low 64))))
            (= low 127) (emit "Control-?")
            :else (emit (char low)))))

      (nth node 1)
      (let [style (nth params 0)]
        (cond
          (nil? style) (emit-pr c)
          (= style \o) (do (emit "\\o")
                           (emit (kernel/integer-text (int c) 8 false false 3 \0 \, 3 10 nil)))
          (= style \u) (do (emit "\\u")
                           (emit (kernel/integer-text (int c) 16 false false 4 \0 \, 3 10 nil)))
          :else (throw (IllegalArgumentException. (str "No matching clause: " style)))))

      :else (if (char? c) (emit c) (emit (print-str c))))
    nav))

(defn- fixed
  "~w,d,k,overflowchar,padcharF."
  [node params nav]
  (let [[arg nav] (next-arg nav)
        [w d k overflow pad] params]
    (emit (kernel/fixed arg w d k overflow pad (nth node 1)))
    nav))

(defn- exponential
  "~w,d,e,k,overflowchar,padchar,exponentcharE, and ~G."
  [node params nav general]
  (let [[arg nav] (next-arg nav)
        [w d e k overflow pad mark] params]
    (emit (if general
            (kernel/general arg w d e k overflow pad mark (nth node 1))
            (kernel/exponential arg w d e k overflow pad mark (nth node 1))))
    nav))

(defn- dollar
  "~d,n,w,padchar$."
  [node params nav]
  (let [[arg nav] (next-arg nav)
        [d n w pad] params]
    (emit (kernel/dollar arg d n w pad (nth node 2) (nth node 1)))
    nav))

(defn- format-fresh-line
  "A fresh line as ~& writes it: always a newline in a ~(...~) clause, whose
  writer keeps no column."
  []
  (if (kernel/case-out) (emit "\n") (fresh-line)))

(defn- tabulate
  "~colnum,colincT, ~colrel,colinc@T: blanks to a column of the pretty print."
  [node params nav]
  (if (and (nil? (kernel/case-out)) (kernel/tab (nth params 0) (nth params 1) (nth node 1)))
    nav
    (not-pretty)))

(defn- goto
  "~n*, ~n:* and ~n@*: n arguments on, back, or to argument n."
  [node params nav]
  (let [n (nth params 0)]
    (if (nth node 1)
      (reposition-absolute nav (or n 0))
      (let [n (or n 1)]
        (reposition-relative nav (if (nth node 2) (- n) n))))))

(defn- format-arg
  "The next argument as a format and the navigator past it."
  [nav]
  (let [[f nav] (next-arg nav)]
    [(cached-format f) nav]))

(defn- indirect
  "~?: the format the next argument is, over the list after it; ~@? over the
  arguments that follow."
  [node nav base]
  (let [[sub nav] (format-arg nav)]
    (if (nth node 1)
      (execute sub nav base)
      (let [[args nav] (next-arg nav)]
        (execute sub (init-nav args) base)
        nav))))

(defn- convert-case
  "~(...~) lowercase, ~:( words capitalized, ~@( the first word capitalized,
  ~:@( uppercase."
  [node nav base]
  (let [at (nth node 1)
        colon (nth node 2)
        mode (cond (and at colon) 3 colon 1 at 2 :else 0)
        state (kernel/case-state)
        outer (or (kernel/case-out) (fn [x] (print x)))]
    (kernel/with-case-out (fn [x] (outer (kernel/case-convert mode state x)))
      (fn [] (execute (first (nth node 5)) nav base)))))

(defn- conditional
  "~[...~;...~] by index (the argument, or the parameter), ~:[false~;true~]
  by truth, ~@[...~] when the argument is true, which it leaves to the
  clause."
  [node params nav base]
  (let [clauses (nth node 5)]
    (cond
      (nth node 2)
      (let [[arg nav] (next-arg nav)
            clause (if arg (second clauses) (first clauses))]
        (if clause (execute clause nav base) nav))

      (nth node 1)
      (let [[arg past] (next-arg nav)]
        (if arg
          (if-let [clause (first clauses)] (execute clause nav base) nav)
          past))

      :else
      (let [[arg nav] (if-let [selector (nth params 0)] [selector nav] (next-arg nav))
            _ (when (nil? arg)
                (throw (NullPointerException. "Cannot invoke \"Object.getClass()\" because \"x\" is null")))
            clause (if (or (neg? arg) (>= arg (count clauses)))
                     (first (nth node 6))
                     (nth clauses (int arg)))]
        (if clause (execute clause nav base) nav)))))

(defn- iterate-args
  "~{...~} over the list argument, ~:{ over its sublists, ~@{ over the
  arguments that follow, ~:@{ over those as sublists; at most the parameter's
  count of times, at least once when closed by ~:}; an empty body takes the
  format from the arguments."
  [node params nav base]
  (let [at (nth node 1)
        colon (nth node 2)
        limit (nth params 0)
        once (nth node 7)
        [clause nav] (let [body (first (nth node 5))]
                       (if (empty? body) (format-arg nav) [body nav]))
        done? (fn [n empty-args]
                (or (and empty-args (or (not once) (> n 0)))
                    (and limit (>= n limit))))]
    (cond
      (and at colon)
      (loop [n 0 nav nav]
        (if (done? n (empty? (nth nav 1)))
          nav
          (let [[sublist nav] (next-arg-or-nil nav)
                result (execute clause (init-nav sublist) nav)]
            (if (and (abort? result) (= :colon-up-arrow (first result)))
              nav
              (recur (inc n) nav)))))

      colon
      (let [[arg-list nav] (next-arg nav)]
        (loop [n 0 arg-list arg-list]
          (if (done? n (empty? arg-list))
            nav
            (let [result (execute clause (init-nav (first arg-list)) (init-nav (next arg-list)))]
              (if (and (abort? result) (= :colon-up-arrow (first result)))
                nav
                (recur (inc n) (next arg-list)))))))

      at
      (loop [n 0 nav nav last-position -1]
        (when (and (not limit) (= (nth nav 2) last-position) (> n 1))
          (throw (RuntimeException. "%@{ construct not consuming any arguments: Infinite loop!")))
        (if (done? n (empty? (nth nav 1)))
          nav
          (let [result (execute clause nav base)]
            (cond
              (not (abort? result)) (recur (inc n) result (nth nav 2))
              (= :up-arrow (first result)) (second result)
              :else result))))

      :else
      (let [[arg-list nav] (next-arg nav)]
        (loop [n 0 args (init-nav arg-list) last-position -1]
          (when (and (not limit) (= (nth args 2) last-position) (> n 1))
            (throw (RuntimeException. "%{ construct not consuming any arguments: Infinite loop!")))
          (if (done? n (empty? (nth args 1)))
            nav
            (let [result (execute clause args base)]
              (if (abort? result)
                nav
                (recur (inc n) result (nth args 2))))))))))

(defn- render-clauses
  "The text of each clause run in turn, and the navigator after them; a ~^
  ends them, dropping the clause it ended."
  [clauses nav base]
  (loop [clauses (seq clauses) texts [] nav nav]
    (if clauses
      (let [result (volatile! nil)
            text (kernel/with-case-out nil
                   (fn [] (with-out-str (vreset! result (execute (first clauses) nav base)))))
            result @result]
        (cond
          (not (abort? result)) (recur (next clauses) (conj texts text) result)
          (= :up-arrow (first result)) [texts (second result)]
          :else (recur (next clauses) (conj texts text) [nil nil nil false])))
      [texts nav])))

(defn- justify
  "~mincol,colinc,minpad,padchar<...~>: the clauses' texts spread over
  mincol columns (more by colinc), the padding between them, before the
  first with ~:< and after the last with ~@<; a first clause ended by ~:; is
  written first when the rest would pass max-columns."
  [node params nav base]
  (let [[eol-texts eol-nav] (when-let [else (nth node 6)] (render-clauses else nav base))
        nav (or eol-nav nav)
        eol (first eol-texts)
        [limits nav] (if-let [separator (nth node 8)]
                       (realize-params (nth separator 3) nav)
                       [[nil nil] nav])
        min-remaining (or (nth limits 0) 0)
        max-columns (nth limits 1)
        ;; without its own max-columns, ~< asks the writer for the margin, which
        ;; only a pretty writer answers
        _ (when (and (nil? max-columns) (or (kernel/case-out) (not (kernel/active?))))
            (not-pretty))
        [texts nav] (render-clauses (nth node 5) nav base)
        at (nth node 1)
        colon (nth node 2)
        [mincol colinc minpad padchar] params
        slots (max 1 (+ (dec (count texts)) (if colon 1 0) (if at 1 0)))
        chars (reduce + 0 (map count texts))
        least (+ chars (* slots minpad))
        columns (if (<= least mincol)
                  mincol
                  (+ mincol (* colinc (inc (divided (- least mincol 1) colinc)))))
        total (- columns chars)
        pad (max minpad (quot total slots))
        pad-text (kernel/padding pad padchar)]
    (when eol
      (cond
        (or (kernel/case-out) (not (kernel/active?))) (not-pretty)
        (nil? max-columns) (throw (NullPointerException.
                                   "Cannot invoke \"Object.getClass()\" because \"x\" is null"))
        :else (kernel/eol eol (+ min-remaining columns) max-columns)))
    (loop [extra (- total (* pad slots))
           texts texts
           pad-only (or colon (and (= 1 (count texts)) (not at)))]
      (when (seq texts)
        (emit (str (when-not pad-only (first texts))
                   (when (or pad-only (next texts) at) pad-text)
                   (when (pos? extra) (str padchar))))
        (recur (dec extra) (if pad-only texts (next texts)) false)))
    nav))

(defn- logical-block
  "~<prefix~;body~;suffix~:>: the body a logical block over the list argument,
  between the literal texts the first and last clauses start with (( and )
  for ~:< when absent)."
  [node nav base]
  (let [clauses (nth node 5)
        n (count clauses)
        lead (fn [clause] (let [x (first clause)] (when (string? x) x)))
        prefix (cond (> n 1) (lead (nth clauses 0)) (nth node 2) "(")
        body (nth clauses (if (> n 1) 1 0))
        suffix (cond (> n 2) (lead (nth clauses 2)) (nth node 2) ")")
        [arg nav] (next-arg nav)]
    (cond
      (or (kernel/case-out) (not (kernel/active?)))
      (if (and *print-level* (<= *print-level* 0)) (emit "#") (not-pretty))
      :else
      (pprint-logical-block :prefix prefix :suffix suffix
        (execute body (init-nav arg) base)))
    nav))

(defn- up-and-out
  "~^: ends the enclosing run when no argument is left (~:^ when the
  enclosing ~:{ has no sublist left), or as its parameters say: one zero, two
  equal, three in order."
  [node params nav base]
  (let [[a b c] params
        colon (nth node 2)
        stop (cond
               (and a b c) (<= a b c)
               (and a b) (= a b)
               a (= a 0)
               :else (empty? (nth (if colon base nav) 1)))]
    (if stop
      (list (if colon :colon-up-arrow :up-arrow) nav)
      nav)))

(defn- write-arg
  "~W: the argument written through the pretty print dispatch, ending the
  enclosing run when *print-length* cut it; ~:W pretty, ~@W without length
  or level limits, as write writes them. In a ~(...~) clause what is written
  is converted as one piece, a string or a character written as pr writes it
  in the pieces pr writes. A collection the dispatch would lay out as a
  logical block is the oracle's refusal where *out* is no pretty writer (a
  ~(...~) clause, a ~<...~> segment, a formatter-out outside a pretty
  print)."
  [node nav]
  (let [[arg nav] (next-arg nav)
        at (nth node 1)
        colon (nth node 2)
        options (concat (when at [:level nil :length nil]) (when colon [:pretty true]))]
    (when (and (not (or at colon)) *print-pretty* (coll? arg)
               (or (kernel/case-out) (not (kernel/active?)))
               (not (kernel/length-reached *print-length*)))
      (not-pretty))
    (cond
      (kernel/case-out)
      (let [reached (volatile! false)]
        (let [text (kernel/with-case-out nil
                     (fn []
                       (with-out-str
                         (if (or at colon)
                           (apply write arg options)
                           (vreset! reached (write-out arg))))))]
          (if (and (or (string? arg) (char? arg)) (= text (pr-str arg)))
            (emit-pr arg)
            (emit text)))
        (if @reached (list :up-arrow nav) nav))

      (or at colon) (do (apply write arg options) nav)
      (write-out arg) (list :up-arrow nav)
      :else nav)))

(defn- conditional-newline
  "~_ linear, ~:_ fill, ~@_ miser, ~:@_ mandatory: a conditional newline of
  the pretty print."
  [node nav]
  (if (or (kernel/case-out) (not (kernel/active?)))
    (not-pretty)
    (pprint-newline (if (nth node 2)
                      (if (nth node 1) :mandatory :fill)
                      (if (nth node 1) :miser :linear))))
  nav)

(defn- indent-block
  "~nI: the logical block's indentation n past its start, ~n:I past the
  column."
  [node params nav]
  (if (or (kernel/case-out) (not (kernel/active?)))
    (not-pretty)
    (pprint-indent (if (nth node 2) :current :block) (nth params 0)))
  nav)

(defn- run-directive
  "Runs the directive node over nav with its realized params; base is the
  navigator a ~:^ and the clauses consult."
  [node params nav base]
  (case (nth node 0)
    \A (ascii node params nav false)
    \S (ascii node params nav true)
    \D (integer-arg node nav 10 params)
    \B (integer-arg node nav 2 params)
    \O (integer-arg node nav 8 params)
    \X (integer-arg node nav 16 params)
    \R (radix node params nav)
    \P (plural node nav)
    \C (character node params nav)
    \F (fixed node params nav)
    \E (exponential node params nav false)
    \G (exponential node params nav true)
    \$ (dollar node params nav)
    \% (do (dotimes [_ (nth params 0)] (emit "\n")) nav)
    \& (let [n (nth params 0)]
         (when (pos? n) (format-fresh-line))
         (dotimes [_ (dec n)] (emit "\n"))
         nav)
    \| (do (dotimes [_ (nth params 0)] (emit \formfeed)) nav)
    \~ (do (emit (kernel/padding (nth params 0) \~)) nav)
    \newline (do (when (nth node 1) (emit "\n")) nav)
    \T (tabulate node params nav)
    \* (goto node params nav)
    \? (indirect node nav base)
    \( (convert-case node nav base)
    \[ (conditional node params nav base)
    \{ (iterate-args node params nav base)
    \< (if (nth node 7) (logical-block node nav base) (justify node params nav base))
    \^ (up-and-out node params nav base)
    \W (write-arg node nav)
    \_ (conditional-newline node nav)
    \I (indent-block node params nav)
    (throw (NullPointerException.
            "Cannot invoke \"clojure.lang.IFn.applyTo(clojure.lang.ISeq)\" because \"f\" is null"))))

(defn- execute
  "Runs the format fmt over the navigator nav: the navigator past what it
  took, or the exit of a ~^ that ended it. base is the navigator of the
  enclosing directive, nil at the top, where each directive's is its own. A
  foreign format is walked as the oracle walks it: no seq is its
  IllegalArgumentException, a non-empty one holds no directive it can run."
  [fmt nav base]
  (if (seq? fmt)
    (if (seq (second fmt))
      (throw (NullPointerException.
              "Cannot invoke \"clojure.lang.IFn.applyTo(clojure.lang.ISeq)\" because \"f\" is null"))
      nav)
    (let [n (count fmt)]
      (loop [i 0 nav nav]
        (if (< i n)
          (let [node (nth fmt i)]
            (if (string? node)
              (do (emit node) (recur (inc i) nav))
              (let [[params nav] (if (nth node 4) (realize-params (nth node 3) nav) [(nth node 3) nav])
                    result (run-directive node params nav (or base nav))]
                (if (abort? result) result (recur (inc i) result)))))
          nav)))))

(defn- format-on-out
  "Runs the format over nav on *out*, inside a pretty print of its own when
  it needs one and *out* is not the pretty writer of one already."
  [fmt nav]
  (if (and (needs-pretty? fmt) (not (kernel/active?)))
    (kernel/call (fn [] (execute fmt nav nil)) *print-right-margin* *print-miser-width*)
    (execute fmt nav nil))
  nil)

(defn- execute-format
  "Runs the format over nav on the stream: nil answers the text, true writes
  to *out*, a writer to itself."
  [stream fmt nav]
  (cond
    (nil? stream) (kernel/with-case-out nil (fn [] (with-out-str (format-on-out fmt nav))))
    (true? stream) (format-on-out fmt nav)
    :else (binding [*out* stream] (kernel/with-case-out nil (fn [] (format-on-out fmt nav))))))

(defn cl-format
  "Formats args by the control string format-in, Common Lisp's format
  directives over Clojure values: to a string answered when writer is nil,
  to *out* when it is true, else to writer itself (answering nil). ~A and ~S
  print as print and pr do, ~W through the pretty print dispatch, ~{ walks
  any seqable, ~:[ tests Clojure truth, and ~<...~:>, ~_ and ~I lay text out
  as the pretty printer does."
  [writer format-in & args]
  (execute-format writer (cached-format format-in) (init-nav args)))

(defn- formatter-fn
  "The function formatter makes of format-in."
  [format-in]
  (let [fmt (cached-format format-in)]
    (fn [stream & args]
      (execute-format stream fmt (init-nav args)))))

(defn- formatter-out-fn
  "The function formatter-out makes of format-in."
  [format-in]
  (let [fmt (cached-format format-in)]
    (fn [& args]
      (execute fmt (init-nav args) nil)
      nil)))

;; The two macros name the function they expand to by building its symbol: a
;; template spelling the name would keep the function -- and the whole format
;; executor -- in every program that loads clojure.pprint and expands at run
;; time (macroexpand keeps every expander, and the dispatch tables of the
;; compiled backends count a template's symbols as names a call may resolve).

(defmacro formatter
  "A function of a stream and arguments that formats them by format-in,
  compiled once, as cl-format would: answering the text for a nil stream."
  [format-in]
  (list (list 'var (symbol "clojure.pprint" "formatter-fn")) format-in))

(defmacro formatter-out
  "A function of arguments that formats them by format-in, compiled once, to
  *out* as it is: meant for a pretty print dispatch function, whose *out* is
  the pretty writer."
  [format-in]
  (list (list 'var (symbol "clojure.pprint" "formatter-out-fn")) format-in))
