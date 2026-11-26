(ns clojure.pprint
  "The pretty printer, built into rontolisp. pprint and write print a value
  within a right margin, breaking it into lines where it has to; what a
  value looks like is up to a dispatch function, simple-dispatch for data
  and code-dispatch for code, which a program may extend or replace;
  cl-format is Common Lisp's format. Written here from the documentation of
  Clojure's namespace of the same name, from the Common Lisp HyperSpec
  (22.2 and 22.3) and from what clj 1.12.6 prints; the page itself is laid
  out by the run-time library (rontolisp.internal.pprint)."
  (:require [rontolisp.internal.pprint :as k]))

(def ^:dynamic *print-pretty*
  "Whether write-out goes through the dispatch function; false prints as pr."
  true)

(def ^:dynamic *print-right-margin*
  "The column a pretty-printed line should stay short of; nil for no limit."
  72)

(def ^:dynamic *print-miser-width*
  "A logical block opening within this many columns of the right margin is
  laid out in miser style, where its miser newlines break; nil for never."
  40)

(def ^:dynamic *print-suppress-namespaces*
  "Whether code-dispatch and ~W write a symbol without its namespace."
  nil)

(def ^:dynamic *print-radix*
  "Whether an integer or a ratio is written with a mark of its base: #b,
  #o, #x, #NNr, or a closing point in base ten."
  nil)

(def ^:dynamic *print-base*
  "The base pprint and write spell integers and ratios in."
  10)

(def ^:private ^:dynamic *numbers-in-base*
  "True inside a pprint or write that found *print-base* or *print-radix*
  changed: there a number is written in that base wherever pr would write
  it."
  false)

(defn- pr-number
  "x as pr writes it, but an integer or a ratio in *print-base* and
  *print-radix* where pprint or write asked for that."
  [x]
  (let [spelled (when *numbers-in-base* (k/number-string x *print-base* *print-radix*))]
    (if spelled (print spelled) (pr x))))

;; The layout marks a dispatch function makes.

(defmacro pprint-logical-block
  "Runs body as one logical block of the pretty print in progress: its
  conditional newlines break together or not at all, and the lines it breaks
  onto are indented under where it began. Ahead of body come the options
  :prefix and :suffix, strings written around it, and :per-line-prefix, a
  string repeated at the start of every line it breaks onto. Nested deeper
  than *print-level*, the block is written as #."
  [& options-and-body]
  (loop [options {} forms options-and-body]
    (if (contains? #{:prefix :per-line-prefix :suffix} (first forms))
      (recur (assoc options (first forms) (second forms)) (nnext forms))
      `(do (when (k/start ~(:prefix options) ~(:per-line-prefix options) ~(:suffix options) *print-level*)
             (try ~@forms (finally (k/end ~(:suffix options)))))
           nil))))

(defn- count-recurs
  "form with a counter, one more each time, handed to every recur that
  belongs to the loop form sits in."
  [counter form]
  (cond
    (and (seq? form) (= 'recur (first form)))
    (apply list 'recur (list 'clojure.core/inc counter) (map (fn [f] (count-recurs counter f)) (rest form)))
    (and (seq? form) (contains? '#{loop fn fn* letfn} (first form))) form
    (seq? form) (apply list (map (fn [f] (count-recurs counter f)) form))
    (vector? form) (mapv (fn [f] (count-recurs counter f)) form)
    :else form))

(defmacro print-length-loop
  "loop, for a dispatch function that walks a collection: once the body has
  run *print-length* times, ... is written in place of the next round."
  [bindings & body]
  (let [rounds (gensym "rounds")]
    `(loop [~rounds 0 ~@bindings]
       (if (and *print-length* (>= ~rounds *print-length*))
         (print "...")
         (do ~@(map (fn [form] (count-recurs rounds form)) body))))))

(defn pprint-newline
  "A place where the logical block being printed may break: kind is
  :linear (all of a block's break, or none), :fill (as few as fit), :miser
  (only in a block laid out in miser style) or :mandatory (always)."
  [kind]
  (k/newline kind))

(defn pprint-indent
  "Indents the lines the logical block being printed breaks onto from here
  on by n columns, counted from where the block's body began (:block) or
  from the column reached (:current)."
  [relative-to n]
  (k/indent relative-to n))

(defn pprint-tab
  "Tabulation in a pretty print, which Clojure does not implement either."
  [kind colnum colinc]
  (throw (UnsupportedOperationException. "pprint-tab is not yet implemented")))

(defn fresh-line
  "Ends the line unless the pretty print in progress is at the start of
  one. Outside a pretty print nothing knows the column, and the line is
  always ended."
  []
  (k/fresh-line))

(defn get-pretty-writer
  "The writer itself: here it is pprint and write that lay a page out, on
  whatever writer they are given."
  [writer]
  writer)

;; What a value looks like.

(defmulti simple-dispatch
  "The dispatch function of data, a multimethod on the value's class: a
  list, a vector, a map and a set are logical blocks whose members break
  onto lines of their own when they do not fit on one, and anything else is
  written as pr writes it. A method added for a class changes how its
  values are pretty printed."
  class)

(def ^:dynamic *print-pprint-dispatch*
  "The function that writes one value of a pretty print: simple-dispatch
  until bound or set to another."
  simple-dispatch)

(defn write-out
  "Writes object as part of the pretty print in progress, through
  *print-pprint-dispatch* (as pr when *print-pretty* is false). Counts
  against *print-length* in the logical block it is written in: past the
  limit it writes ... instead and answers true."
  [object]
  (let [cut (k/length-reached *print-length*)]
    (cond (not *print-pretty*) (pr object)
          cut (print "...")
          :else (do (k/count-object) (*print-pprint-dispatch* object)))
    cut))

(defn- write-metadata
  "Under *print-meta*, the metadata of x ahead of it, as the reader takes
  it back."
  [x]
  (when *print-meta*
    (when-let [m (meta x)]
      (print "^")
      (if (and (= 1 (count m)) (:tag m)) (write-out (:tag m)) (write-out m))
      (print " ")
      (pprint-newline :linear))))

(defn- write-members
  "The members of coll, a blank and a linear newline between two."
  [coll]
  (print-length-loop [members (seq coll)]
    (when members
      (write-out (first members))
      (when (next members)
        (print " ")
        (pprint-newline :linear)
        (recur (next members))))))

(def ^:private reader-marks
  "The two-member lists the reader has a mark for, and the mark."
  {'quote "'", 'clojure.core/deref "@", 'var "#'", 'clojure.core/unquote "~"})

(defn- write-marked
  "Writes the list form as the reader mark it stands for and answers true;
  nil for any other list."
  [form]
  (let [mark (reader-marks (first form))]
    (when (and mark (= 2 (count form)))
      (print mark)
      (write-out (second form))
      true)))

(defn- common-namespace
  "The namespace every key of the map m is qualified by, when the printer
  lifts one out of a map (*print-namespace-maps*); nil otherwise."
  [m]
  (when (and *print-namespace-maps* (seq m) (not (record? m)))
    (let [spaces (map (fn [key] (when (or (keyword? key) (symbol? key)) (namespace key))) (keys m))]
      (when (and (first spaces) (apply = spaces))
        (first spaces)))))

(defn- write-map
  "A map as a logical block of its entries: each entry a block of its own,
  key and value a linear newline apart, a comma between two entries. A map
  whose keys share a namespace is written #:ns{...} with the keys bare."
  [m]
  (let [space (common-namespace m)
        bare (fn [key] (if (keyword? key) (keyword (name key)) (symbol (name key))))]
    (pprint-logical-block :prefix (if space (str "#:" space "{") "{") :suffix "}"
      (print-length-loop [entries (seq m)]
        (when entries
          (pprint-logical-block
            (let [entry (first entries)]
              (write-out (if space (bare (key entry)) (key entry)))
              (print " ")
              (pprint-newline :linear)
              ;; a key is never left without its value
              (k/reset-length)
              (write-out (val entry))))
          (when (next entries)
            (print ", ")
            (pprint-newline :linear)
            (recur (next entries))))))))

(defn- write-data
  "x as simple-dispatch lays it out."
  [x]
  (cond
    (nil? x) (pr nil)
    (map? x) (do (write-metadata x) (write-map x))
    (vector? x) (do (write-metadata x)
                    (pprint-logical-block :prefix "[" :suffix "]" (write-members x)))
    (set? x) (do (write-metadata x)
                 (pprint-logical-block :prefix "#{" :suffix "}" (write-members x)))
    (seq? x) (do (write-metadata x)
                 (when-not (write-marked x)
                   (pprint-logical-block :prefix "(" :suffix ")" (write-members x))))
    (and (symbol? x) *print-suppress-namespaces*) (print (name x))
    :else (pr-number x)))

(defmethod simple-dispatch nil [_] (pr nil))
(defmethod simple-dispatch clojure.lang.IPersistentVector [x] (write-data x))
(defmethod simple-dispatch clojure.lang.IPersistentMap [x] (write-data x))
(defmethod simple-dispatch clojure.lang.IPersistentSet [x] (write-data x))
(defmethod simple-dispatch clojure.lang.IPersistentList [x] (write-data x))
(defmethod simple-dispatch java.lang.Number [x] (write-data x))
(defmethod simple-dispatch java.lang.String [x] (write-data x))
(defmethod simple-dispatch clojure.lang.Keyword [x] (write-data x))
(defmethod simple-dispatch clojure.lang.Symbol [x] (write-data x))
(defmethod simple-dispatch java.lang.Boolean [x] (write-data x))
(defmethod simple-dispatch java.lang.Character [x] (write-data x))
(defmethod simple-dispatch :default [x] (write-data x))

;; cl-format: Common Lisp's format (HyperSpec 22.3). A control string is
;; read once into a tree -- text, and directives holding the clauses they
;; bracket -- and the tree is then walked over the arguments. The walk
;; keeps its place among the arguments in a cursor that it moves in place;
;; a walk of some part answers nil when it ran to its end, :up when a ~^
;; left it (the enclosing iteration goes on to its next round, or stops)
;; and :out when a ~:^ ended the enclosing ~:{ as a whole. The spelling of
;; numbers and the case conversion of ~( are the run-time library's.

(defn- refuse
  "Throws the refusal of a control string: the reason, the string, and a
  caret under the offset it failed at."
  [control reason offset]
  (throw (RuntimeException. (str reason "\n" control "\n" (k/padding offset \space) "^\n"))))

(def ^:private directive-table
  "Per directive character: the names of its parameters and their defaults
  (a parameter named ...char is a character, any other an integer), the
  flags it takes (:any, :one of the two, :at, :colon or :none), the
  character that closes it when it brackets clauses, whether ~; may
  separate them, and whether it needs a pretty print to run in."
  (let [field {:names ["mincol" "colinc" "minpad" "padchar"] :defaults [0 1 0 \space]}
        whole {:names ["mincol" "padchar" "commachar" "commainterval"] :defaults [0 \space \, 3]}
        scaled {:names ["w" "d" "e" "k" "overflowchar" "padchar" "exponentchar"]
                :defaults [nil nil nil 1 nil \space nil]}
        times {:names ["count"] :defaults [1]}
        bare {:names [] :defaults []}]
    {\A (assoc field :flags :any)
     \S (assoc field :flags :any)
     \D (assoc whole :flags :any)
     \B (assoc whole :flags :any)
     \O (assoc whole :flags :any)
     \X (assoc whole :flags :any)
     \R {:names (into ["base"] (:names whole)) :defaults (into [nil] (:defaults whole)) :flags :any}
     \P (assoc bare :flags :any)
     \C {:names ["char-format"] :defaults [nil] :flags :any}
     \F {:names ["w" "d" "k" "overflowchar" "padchar"] :defaults [nil nil 0 nil \space] :flags :at}
     \E (assoc scaled :flags :at)
     \G (assoc scaled :flags :at)
     \$ {:names ["d" "n" "w" "padchar"] :defaults [2 1 0 \space] :flags :any}
     \% (assoc times :flags :none)
     \| (assoc times :flags :none)
     \& (assoc times :flags :none :pretty true)
     \~ {:names ["n"] :defaults [1] :flags :none}
     \newline (assoc bare :flags :one)
     \T {:names ["colnum" "colinc"] :defaults [1 1] :flags :at :pretty true}
     \* {:names ["n"] :defaults [nil] :flags :one}
     \? (assoc bare :flags :at)
     \( (assoc bare :flags :any :closer \))
     \[ {:names ["selector"] :defaults [nil] :flags :one :closer \] :separated true}
     \; {:names ["min-remaining" "max-columns"] :defaults [nil nil] :flags :colon}
     \{ {:names ["max-iterations"] :defaults [nil] :flags :any :closer \}}
     \< (assoc field :flags :any :closer \> :separated true :pretty true)
     \) (assoc bare :flags :none)
     \] (assoc bare :flags :none)
     \} (assoc bare :flags :colon)
     \> (assoc bare :flags :colon)
     \^ {:names ["arg1" "arg2" "arg3"] :defaults [nil nil nil] :flags :colon}
     \W (assoc bare :flags :any :pretty true)
     \_ (assoc bare :flags :any)
     \I {:names ["n"] :defaults [0] :flags :colon}}))

(defn- digit-at?
  [control at]
  (and (< at (count control))
       (let [code (int (nth control at))] (and (>= code 48) (<= code 57)))))

(defn- read-parameter
  "The parameter starting at index at of control: [value next-index], the
  value an integer, a character, :v (the next argument), :# (how many
  arguments are left) or nil for none written there."
  [control at]
  (let [size (count control)
        c (when (< at size) (nth control at))]
    (cond
      (or (digit-at? control at) (and (or (= c \+) (= c \-)) (digit-at? control (inc at))))
      (let [end (loop [i (inc at)] (if (digit-at? control i) (recur (inc i)) i))
            digits (subs control (if (= c \+) (inc at) at) end)]
        ;; a parameter is an int to the oracle, which refuses one that is not
        (when-not (<= -2147483648 (bigint digits) 2147483647)
          (throw (NumberFormatException. (str "For input string: \"" digits "\""))))
        [(parse-long digits) end])
      (and (= c \') (< (inc at) size)) [(nth control (inc at)) (+ at 2)]
      (or (= c \v) (= c \V)) [:v (inc at)]
      (= c \#) [:# (inc at)]
      :else [nil at])))

(defn- read-directive
  "The directive whose tilde is at index tilde of control, checked against
  the table: a map of :char (upper case), :colon, :at, :params (the values
  written, in order), :offset (where its character stands) and :next (the
  index after it)."
  [control tilde]
  (let [size (count control)
        [params at] (loop [params [] offsets [] at (inc tilde) commas false]
                      (let [[value next] (read-parameter control at)]
                        (cond
                          (and (< next size) (= \, (nth control next)))
                          (recur (conj params value) (conj offsets at) (inc next) true)
                          (and commas (= next at))
                          (refuse control "Badly formed parameters in format directive" at)
                          (= next at) [(mapv vector params offsets) at]
                          :else [(mapv vector (conj params value) (conj offsets at)) next])))
        [flags at] (loop [flags {} at at]
                     (let [c (when (< at size) (nth control at))
                           flag (cond (= c \:) :colon (= c \@) :at)]
                       (cond
                         (nil? flag) [flags at]
                         (contains? flags flag)
                         (refuse control (str "Flag \"" c "\" appears more than once in a directive") at)
                         :else (recur (assoc flags flag at) (inc at)))))]
    (when (>= at size)
      (if (and (seq params) (empty? flags))
        (throw (StringIndexOutOfBoundsException. "Index 0 out of bounds for length 0"))
        (throw (NullPointerException.
                 "Cannot invoke \"Character.charValue()\" because \"directive\" is null"))))
    (let [written (nth control at)
          c (first (clojure.string/upper-case (str written)))
          entry (directive-table c)
          colon (contains? flags :colon)
          at-flag (contains? flags :at)
          allowed (:flags entry)]
      (when-not entry
        (refuse control (str "Directive \"" written "\" is undefined") at))
      (when (and at-flag (contains? #{:colon :none} allowed))
        (refuse control (str "\"@\" is an illegal flag for format directive \"" c "\"") (:at flags)))
      (when (and colon (contains? #{:at :none} allowed))
        (refuse control (str "\":\" is an illegal flag for format directive \"" c "\"") (:colon flags)))
      (when (and colon at-flag (= allowed :one))
        (refuse control (str "Cannot combine \"@\" and \":\" flags for format directive \"" c "\"")
                (min (:at flags) (:colon flags))))
      (when (> (count params) (count (:names entry)))
        (refuse control
                (str "Too many parameters for directive \"" c "\": " (count params)
                     (if (= 1 (count params)) " was" " were") " specified but only "
                     (count (:names entry)) (if (= 1 (count (:names entry))) " is" " are") " allowed")
                (inc tilde)))
      (doseq [[[value offset] name] (map vector params (:names entry))]
        (let [wants-char (or (= name "char-format") (clojure.string/ends-with? name "char"))]
          (when (and (some? value) (not (keyword? value)) (not= wants-char (char? value)))
            (refuse control
                    (str "Parameter " name " has bad type in directive \"" c "\": class java.lang."
                         (if (char? value) "Character" "Integer"))
                    offset))))
      {:char c :colon colon :at at-flag :params (mapv first params) :offset at :next (inc at)})))

(defn- blank?
  [c]
  (or (= c \space) (= c \tab)))

(defn- read-flat
  "control as a vector of its pieces in order: strings and directives. A
  ~newline swallows the blanks after it, unless it has the colon, and
  stands for a line end only with the at-sign."
  [control]
  (let [size (count control)]
    (loop [pieces [] at 0]
      (if (>= at size)
        pieces
        (let [tilde (clojure.string/index-of control "~" at)]
          (cond
            (nil? tilde) (conj pieces (subs control at))
            (> tilde at) (recur (conj pieces (subs control at tilde)) tilde)
            :else
            (let [directive (read-directive control tilde)]
              (if (= \newline (:char directive))
                (let [after (if (:colon directive)
                              (:next directive)
                              (loop [i (:next directive)]
                                (if (and (< i size) (blank? (nth control i))) (recur (inc i)) i)))]
                  (recur (if (:at directive) (conj pieces "\n") pieces) after))
                (recur (conj pieces directive) (:next directive))))))))))

(declare nest)

(defn- nest-bracket
  "The bracket directive opener, at index start of the flat pieces, with
  the clauses up to its closer: [directive next-index]. The directive gains
  :clauses, :separators (the ~; ahead of each clause but the first) and
  :closer."
  [control pieces start]
  (let [opener (nth pieces start)
        entry (directive-table (:char opener))
        closer (:closer entry)]
    (loop [clauses [] separators [] at (inc start)]
      (let [[clause stop] (nest control pieces at closer)
            ending (when (< stop (count pieces)) (nth pieces stop))]
        (cond
          (nil? ending) (refuse control "No closing bracket found." (:offset opener))
          (= closer (:char ending))
          [(assoc opener :clauses (conj clauses clause) :separators separators :closer ending) (inc stop)]
          (not (:separated entry))
          (refuse control
                  (if (:colon ending)
                    "An else clause (\"~:;\") is in a bracket type that doesn't support it."
                    "A separator (\"~;\") is in a bracket type that doesn't support it.")
                  (:offset opener))
          (and (= \[ (:char opener)) (not (:colon ending)) (some :colon separators))
          (refuse control
                  "A plain clause (with \"~;\") follows an else clause (\"~:;\") inside bracket construction."
                  (:offset opener))
          (and (= \< (:char opener)) (:colon ending) (seq separators))
          (refuse control
                  (if (:colon (first separators))
                    "Two else clauses (\"~:;\") inside bracket construction."
                    "The else clause (\"~:;\") is only allowed in the first position for this directive.")
                  (:offset opener))
          :else (recur (conj clauses clause) (conj separators ending) (inc stop)))))))

(defn- nest
  "The pieces from index at on as one clause -- a vector of strings and
  directives, the bracket ones holding their own clauses -- up to the
  directive closing or separating the bracket whose closer is given (to
  the end without one): [clause stop-index]."
  [control pieces at closer]
  (loop [clause [] at at]
    (if (>= at (count pieces))
      [clause at]
      (let [piece (nth pieces at)]
        (cond
          (string? piece) (recur (conj clause piece) (inc at))
          (and closer (or (= closer (:char piece)) (= \; (:char piece)))) [clause at]
          (:closer (directive-table (:char piece)))
          (let [[bracket next] (nest-bracket control pieces at)]
            (recur (conj clause bracket) next))
          :else (recur (conj clause piece) (inc at)))))))

(defn- pretty-clause?
  "Whether clause holds, at any depth, a directive that runs in a pretty
  print."
  [clause]
  (boolean (some (fn [piece]
                   (and (map? piece)
                        (or (:pretty (directive-table (:char piece)))
                            (some pretty-clause? (:clauses piece)))))
                 clause)))

(defn- compile-format
  "The control string read into what cl-format runs: a map of its pieces
  (::pieces) and whether it needs a pretty print (::pretty)."
  [control]
  (let [pieces (read-flat control)
        [clause _] (nest control pieces 0 nil)]
    {::pieces clause ::pretty (pretty-clause? clause)}))

(def ^:private compiled
  "The control strings compiled so far; emptied when it has grown to 512."
  (atom {}))

(defn- as-format
  "What cl-format was handed as its format, ready to run: a control string
  compiled (once per string), a compiled format as it is."
  [format-in]
  (cond
    (string? format-in)
    (or (get @compiled format-in)
        (let [ready (compile-format format-in)]
          (when (>= (count @compiled) 512) (reset! compiled {}))
          (swap! compiled assoc format-in ready)
          ready))
    (and (map? format-in) (contains? format-in ::pieces)) format-in
    ;; anything else is walked as if it were compiled: nothing to walk in
    ;; what is empty, and nothing that can run in what is not
    (empty? (seq format-in)) {::pieces [] ::pretty false}
    :else (throw (NullPointerException.
                   "Cannot invoke \"clojure.lang.IFn.applyTo(clojure.lang.ISeq)\" because \"f\" is null"))))

;; The cursor over the arguments: a volatile holding where the walk is.

(defn- cursor
  [arguments]
  (volatile! {:all arguments :rest (seq arguments) :at 0 :moved false}))

(defn- take-argument
  "The next argument, moving the cursor past it. Right after a ~* moved the
  cursor there is always one more to take, nil past the end, as the
  oracle's is."
  [cur]
  (let [{:keys [rest at moved] :as place} @cur]
    (when-not (or rest moved)
      (throw (Exception. "Not enough arguments for format definition")))
    (vreset! cur (assoc place :rest (next rest) :at (inc at) :moved false))
    (first rest)))

(defn- move-to
  "Moves the cursor to the argument numbered n from the first."
  [cur n]
  (let [place @cur]
    (vreset! cur (assoc place :rest (seq (drop n (:all place))) :at n :moved true))
    nil))

(defn- skip
  "Moves the cursor n arguments on from where it is."
  [cur n]
  (let [place @cur]
    (vreset! cur (assoc place :rest (seq (drop n (:rest place))) :at (+ (:at place) n) :moved true))
    nil))

(defn- left
  "How many arguments the cursor has not taken."
  [cur]
  (count (:rest @cur)))

(defn- more?
  "Whether the cursor has an argument left to take; unlike a count, this
  can be asked of arguments without end."
  [cur]
  (some? (:rest @cur)))

;; Writing.

(def ^:private ^:dynamic *plain-out*
  "True where what a clause writes is collected or converted on its way
  out, so that nothing there can take part in a pretty print."
  false)

(defn- emit
  "Writes piece, a string or a character, through the case conversion of
  the ~( clauses around it."
  [piece]
  (let [convert (k/case-out)]
    (print (if convert (convert piece) piece))))

(defn- need-pretty
  "Refuses what only a pretty print can do, where there is none."
  []
  (when (or *plain-out* (not (k/active?)))
    (throw (ClassCastException. "*out* is not a pretty writer"))))

(declare walk run-format)

(defn- actual
  "The parameters of directive as the walk finds them: the defaults where
  none was written, the next argument for a v and the number of arguments
  left for a #, the last one written resolved first."
  [directive cur]
  (let [defaults (:defaults (directive-table (:char directive)))
        written (:params directive)
        resolved (reduce (fn [values i]
                           (let [value (nth written i)]
                             (assoc values i
                                    (cond (= value :v) (take-argument cur)
                                          (= value :#) (left cur)
                                          :else value))))
                         written
                         (reverse (range (count written))))]
    (mapv (fn [i]
            (let [value (when (< i (count resolved)) (nth resolved i))
                  given (and (< i (count written)) (some? (nth written i)))]
              (if given value (nth defaults i))))
          (range (count defaults)))))

(defn- in-field
  "text in a field of at least mincol columns, padded with minpad copies
  of padchar and then colinc more at a time: on the left under at-flag."
  [text mincol colinc minpad padchar at-flag]
  (let [short (- mincol (count text) minpad)
        more (if (pos? short) (* colinc (quot (+ short (dec colinc)) colinc)) 0)
        padding (k/padding (+ minpad more) padchar)]
    (if at-flag (str padding text) (str text padding))))

(defn- spelled
  "x as ~A writes it: an integer or a ratio in *print-base* and
  *print-radix*, anything else as print does."
  [x]
  (or (k/number-string x *print-base* *print-radix*) (print-str x)))

(defn- character-name
  "The character of the given code as ~:C names it."
  [code]
  (let [code (bit-and code 255)]
    (cond
      (> code 127) (str "Meta-" (character-name (- code 128)))
      (= code 8) "Backspace"
      (= code 9) "Tab"
      (= code 10) "Newline"
      (= code 13) "Return"
      (= code 32) "Space"
      (= code 127) "Control-?"
      (< code 32) (str "Control-" (char (+ code 64)))
      :else (str (char code)))))

(defn- write-character
  [directive params cur]
  (let [c (take-argument cur)
        style (first params)]
    (cond
      (:colon directive) (emit (character-name (int c)))
      (and (:at directive) (= style \o)) (emit (str "\\o" (k/integer-text (int c) 8 nil nil 3 \0 \, 3 10 nil)))
      (and (:at directive) (= style \u)) (emit (str "\\u" (k/integer-text (int c) 16 nil nil 4 \0 \, 3 10 nil)))
      (and (:at directive) (nil? style)) (emit (pr-str c))
      (:at directive) (throw (IllegalArgumentException. (str "No matching clause: " style)))
      (char? c) (emit c)
      :else (emit (print-str c)))))

(defn- repeated
  "n copies of text; n may be a character, which stands for its code."
  [n text]
  (apply str (repeat (if (char? n) (int n) (long n)) text)))

(defn- choose
  "~[: the clause the argument (or the selector) numbers, the ~:; clause
  past the last; ~:[ the first clause for a false argument and the second
  for a true one; ~@[ its clause for a true argument, which stays to be
  taken."
  [directive params cur]
  (let [has-else (true? (:colon (last (:separators directive))))
        clauses (if has-else (vec (butlast (:clauses directive))) (:clauses directive))
        else-clause (when has-else (last (:clauses directive)))]
    (cond
      (:colon directive) (let [clause (if (take-argument cur) (second clauses) (first clauses))]
                           (when clause (walk clause cur)))
      (:at directive) (let [place @cur
                            value (take-argument cur)]
                        (when value
                          (vreset! cur place)
                          (walk (first clauses) cur)))
      :else (let [selector (if (some? (first params)) (first params) (take-argument cur))
                  number (cond (number? selector) selector
                               (nil? selector) (throw (NullPointerException.
                                                        "Cannot invoke \"Object.getClass()\" because \"x\" is null"))
                               :else (throw (ClassCastException.
                                              (str "class " (.getName (class selector))
                                                   " cannot be cast to class java.lang.Number"))))
                  clause (if (and (>= number 0) (< number (count clauses)))
                           (nth clauses number)
                           else-clause)]
              (when clause (walk clause cur))))))

(def ^:private ^:dynamic *lists-left*
  "What ~:^ asks: a function answering whether the arguments one level up
  from the clause it stands in have run out -- the sublists still to come
  in a round of ~:{, the arguments themselves at the top of a format. Never
  true in a round of ~{ or ~@{, whose list was still to come when it
  began."
  (fn [] false))

(defn- rounds
  "~{: the body walked round after round. over is the cursor a round takes
  its arguments from, or nil when every round has a sublist of its own,
  taken from lists (a cursor) by next-list. Stops when the arguments run
  out, after limit rounds, or when a round is left by ~^ (by ~:^, when the
  rounds are over sublists)."
  [directive body limit over lists]
  (let [once (:colon (:closer directive))
        name (if (:at directive) "%@{" "%{")]
    (loop [n 0]
      (let [more (if over (more? over) (more? lists))]
        (when (and (or more (and once (zero? n))) (or (nil? limit) (< n limit)))
          (if over
            (let [before (:at @over)
                  status (binding [*lists-left* (fn [] false)] (walk (force body) over))]
              (when (nil? status)
                ;; with no limit on the rounds, one that took nothing would
                ;; be followed by the same round for ever
                (when (and (nil? limit) (= before (:at @over)) (more? over))
                  (throw (RuntimeException.
                           (str name " construct not consuming any arguments: Infinite loop!"))))
                (recur (inc n))))
            (let [sub (cursor (if more (take-argument lists) nil))
                  status (binding [*lists-left* (fn [] (not (more? lists)))] (walk (force body) sub))]
              (when (not= status :out) (recur (inc n))))))))))

(defn- iterate-over
  [directive params cur]
  (let [limit (first params)
        written (first (:clauses directive))
        ;; an empty body stands for a format taken from the arguments,
        ;; ahead of the list, and read when a first round needs it
        given (when (empty? written) (take-argument cur))
        own (when-not (:at directive) (cursor (seq (take-argument cur))))
        body (delay (if (empty? written) (::pieces (as-format given)) written))]
    (cond
      (and (:colon directive) (:at directive)) (rounds directive body limit nil cur)
      (:colon directive) (rounds directive body limit nil own)
      (:at directive) (rounds directive body limit cur nil)
      :else (rounds directive body limit own nil))
    nil))

(defn- escape
  "~^: :up when no argument is left (one parameter: when it is zero; two:
  when they are equal; three: when they ascend); ~:^ :out, asking about
  the sublists of the ~:{ around it instead."
  [directive params cur]
  (let [[a b c] params
        leave (cond (and (some? a) (some? b) (some? c)) (<= a b c)
                    (and (some? a) (some? b)) (= a b)
                    (some? a) (= a 0)
                    (:colon directive) (*lists-left*)
                    :else (not (more? cur)))]
    (when leave (if (:colon directive) :out :up))))

(defn- segments
  "The text each clause of a justification writes, in order, and how the
  last one walked ended: [texts status]. A clause left by ~^ ends them, its
  own text dropped; one left by ~:^ ends them too, its text kept."
  [clauses cur]
  (loop [texts [] clauses clauses]
    (if (empty? clauses)
      [texts nil]
      (let [status (volatile! nil)
            text (binding [*plain-out* true]
                   (with-out-str (vreset! status (walk (first clauses) cur))))]
        (cond (= :up @status) [texts :up]
              (= :out @status) [(conj texts text) :out]
              :else (recur (conj texts text) (rest clauses)))))))

(defn- justify
  "~<...~>: the clauses' texts spread over a field of mincol columns (wider
  by colinc at a time), the padding between them, before the first under
  the colon and after the last under the at-sign. A first clause ending in
  ~:; is written ahead of the rest only where the line has not room for
  them."
  [directive params cur]
  (let [[mincol colinc minpad padchar] params
        spill (true? (:colon (first (:separators directive))))
        columns-written (and spill (some? (second (:params (first (:separators directive))))))
        ;; without a line width of its own a justification asks the pretty
        ;; print for one, once its ~:; clause is written
        _ (when-not spill (need-pretty))
        [spilled spill-status] (when spill (segments (take 1 (:clauses directive)) cur))
        overflow (first spilled)
        _ (when (and spill (not columns-written)) (need-pretty))
        [texts status] (if spill-status
                         [[] spill-status]
                         (segments (if spill (rest (:clauses directive)) (:clauses directive)) cur))
        gaps (+ (dec (count texts)) (if (:colon directive) 1 0) (if (:at directive) 1 0))
        gaps (if (and (zero? gaps) (seq texts)) 1 gaps)
        lead (or (:colon directive) (and (= 1 (count texts)) (not (:at directive))))
        chars (reduce + (map count texts))
        least (+ chars (* gaps minpad))
        width (if (> least mincol)
                (+ mincol (* colinc (quot (+ (- least mincol) (dec colinc)) colinc)))
                mincol)
        room (- width chars)
        each (if (pos? gaps) (quot room gaps) 0)
        extra (if (pos? gaps) (rem room gaps) 0)
        pad (fn [i] (k/padding (+ each (if (< i extra) 1 0)) padchar))
        line (loop [out "" texts texts gap 0 first-text true]
               (if (empty? texts)
                 (if (:at directive) (str out (pad gap)) out)
                 (if (and first-text (not lead))
                   (recur (str out (first texts)) (rest texts) gap false)
                   (recur (str out (pad gap) (first texts)) (rest texts) (inc gap) false))))]
    (let [[spare columns] (when spill (actual (first (:separators directive)) cur))]
      (when (and spill overflow)
        ;; the oracle compares against the width the ~:; names, and has
        ;; none to compare against without it
        (when (nil? columns)
          (throw (NullPointerException. "Cannot invoke \"Object.getClass()\" because \"x\" is null")))
        (when-not (k/eol overflow (+ (count line) (or spare 0)) (or columns *print-right-margin* 72))
          (throw (ClassCastException. "*out* is not a pretty writer")))))
    (emit line)
    ;; a ~:^ that left a clause leaves the whole format with it
    (when (= :out status) :out)))

(defn- fill-style
  "The body of a ~:@> block: a fill newline after every run of blanks in
  its text."
  [clause]
  (vec (mapcat (fn [piece]
                 (if (string? piece)
                   (loop [out [] text piece]
                     (let [blank (clojure.string/index-of text " ")]
                       (if (nil? blank)
                         (if (seq text) (conj out text) out)
                         (let [end (loop [i blank] (if (and (< i (count text)) (= \space (nth text i))) (recur (inc i)) i))]
                           (recur (conj out (subs text 0 end) {:char \_ :colon true :at false :params []})
                                  (subs text end))))))
                   [piece]))
               clause)))

(defn- logical-block
  "~<...~:>: its body walked as a logical block over a list argument. One
  clause is the body;
  two a prefix and the body; three a prefix, the body and a suffix; the
  colon stands for parentheses."
  [directive cur]
  (let [clauses (:clauses directive)
        text (fn [clause] (apply str (filter string? clause)))
        prefix (cond (> (count clauses) 1) (text (first clauses)) (:colon directive) "(")
        suffix (cond (> (count clauses) 2) (text (nth clauses 2)) (:colon directive) ")")
        body (if (> (count clauses) 1) (second clauses) (first clauses))
        body (if (:at (:closer directive)) (fill-style body) body)
        argument (take-argument cur)]
    (need-pretty)
    (let [inner (cursor argument)]
      (pprint-logical-block :prefix prefix :suffix suffix
        (walk body inner)))
    nil))

(declare write)

(defn- write-argument
  "~W: the argument through write-out, as part of the pretty print in
  progress; a value *print-length* cut ends the walk. With a flag it is a
  write of its own -- pretty under the colon, without *print-level* and
  *print-length* under the at-sign."
  [directive cur]
  (let [value (take-argument cur)
        go (fn []
             (cond
               (and (:colon directive) (:at directive)) (do (write value :pretty true :level nil :length nil) false)
               (:colon directive) (do (write value :pretty true) false)
               (:at directive) (do (write value :level nil :length nil) false)
               :else (write-out value)))]
    ;; a collection is a logical block, which a bare ~W can only write into
    ;; a pretty print -- not through the conversion of a ~( either
    (when (and (coll? value)
               (or (not (k/active?))
                   (and (k/case-out) (not (:colon directive)) (not (:at directive)))))
      (throw (ClassCastException. "*out* is not a pretty writer")))
    (when (if (k/case-out)
            ;; under ~( the oracle's printer hands its text over a character
            ;; at a time, and a character is converted unlike a string
            (do (doseq [c (with-out-str (go))] (emit c)) false)
            (go))
      :up)))

(defn- convert-case
  "~(...~): the clause with every piece it writes put in lower case (the
  colon: each word capitalized; the at-sign: the first word capitalized;
  both: upper case), through any conversion already around it."
  [directive cur]
  (let [mode (+ (if (:colon directive) 1 0) (if (:at directive) 2 0))
        state (k/case-state)
        outer (k/case-out)
        convert (fn [piece]
                  (let [converted (k/case-convert mode state piece)]
                    (if outer (outer converted) converted)))]
    (binding [*plain-out* true]
      (k/with-case-out convert (fn [] (walk (first (:clauses directive)) cur))))))

(defn- run-directive
  "Runs one directive at the cursor; answers nil, :up or :out."
  [directive cur]
  (let [c (:char directive)
        params (actual directive cur)
        colon (:colon directive)
        at-flag (:at directive)]
    (case c
      \A (let [[mincol colinc minpad padchar] params]
           (emit (in-field (spelled (take-argument cur)) mincol colinc minpad padchar at-flag))
           nil)
      \S (let [[mincol colinc minpad padchar] params
               value (take-argument cur)]
           (emit (in-field (or (k/number-string value *print-base* *print-radix*) (pr-str value))
                           mincol colinc minpad padchar at-flag))
           nil)
      (\D \B \O \X)
      (let [[mincol padchar commachar interval] params]
        (emit (k/integer-text (take-argument cur) (case c \D 10 \B 2 \O 8 \X 16) colon at-flag
                              mincol padchar commachar interval *print-base* *print-radix*))
        nil)
      \R (let [[base mincol padchar commachar interval] params
               value (take-argument cur)]
           ;; a base that was written makes it a number in that base, even
           ;; when a v found none
           (emit (cond (some? (first (:params directive)))
                       (k/integer-text value base colon at-flag mincol padchar commachar interval
                                       *print-base* *print-radix*)
                       at-flag (k/roman value colon *print-base* *print-radix*)
                       :else (k/english value colon *print-base* *print-radix*)))
           nil)
      \P (do (when colon (move-to cur (dec (:at @cur))))
             (let [one (= 1 (take-argument cur))]
               (emit (if at-flag (if one "y" "ies") (if one "" "s"))))
             nil)
      \C (do (write-character directive params cur) nil)
      \F (let [[w d scale overflow pad] params]
           (emit (k/fixed (take-argument cur) w d scale overflow pad at-flag))
           nil)
      \E (let [[w d e scale overflow pad mark] params]
           (emit (k/exponential (take-argument cur) w d e scale overflow pad mark at-flag))
           nil)
      \G (let [[w d e scale overflow pad mark] params]
           (emit (k/general (take-argument cur) w d e scale overflow pad mark at-flag))
           nil)
      \$ (let [[d n w pad] params]
           (emit (k/dollar (take-argument cur) d n w pad colon at-flag))
           nil)
      \% (do (dotimes [_ (if (char? (first params)) (int (first params)) (long (first params)))] (emit "\n")) nil)
      \| (do (emit (repeated (first params) "\f")) nil)
      \~ (do (emit (repeated (first params) "~")) nil)
      \& (let [n (first params)]
           (when (pos? n)
             (fresh-line)
             (dotimes [_ (dec n)] (emit "\n")))
           nil)
      \T (do (when-not (and (not *plain-out*) (k/tab (first params) (second params) at-flag))
               (throw (ClassCastException. "*out* is not a pretty writer")))
             nil)
      \* (let [n (first params)]
           (cond at-flag (move-to cur (or n 0))
                 colon (move-to cur (- (:at @cur) (or n 1)))
                 :else (skip cur (or n 1)))
           nil)
      \? (let [given (take-argument cur)
               over (if at-flag cur (cursor (seq (take-argument cur))))]
           (binding [*lists-left* (fn [] (not (more? over)))]
             (walk (::pieces (as-format given)) over))
           nil)
      ;; inside the clauses of these, ~:^ asks about the arguments as they
      ;; stood when the directive began
      (\( \[ \<)
      (let [none (not (more? cur))]
        (binding [*lists-left* (fn [] none)]
          (case c
            \( (convert-case directive cur)
            \[ (choose directive params cur)
            \< (if (:colon (:closer directive)) (logical-block directive cur) (justify directive params cur)))))
      \{ (iterate-over directive params cur)
      \^ (escape directive params cur)
      \W (write-argument directive cur)
      \_ (do (need-pretty)
             (pprint-newline (cond (and colon at-flag) :mandatory colon :fill at-flag :miser :else :linear))
             nil)
      \I (do (need-pretty)
             (pprint-indent (if colon :current :block) (first params))
             nil)
      (throw (NullPointerException.
               "Cannot invoke \"clojure.lang.IFn.applyTo(clojure.lang.ISeq)\" because \"f\" is null")))))

(defn- walk
  "Walks clause over the arguments at the cursor: text is written, and each
  directive run in turn until one leaves the clause. Answers nil, :up or
  :out."
  [clause cur]
  (loop [pieces (seq clause)]
    (when pieces
      (let [piece (first pieces)
            status (if (string? piece) (do (emit piece) nil) (run-directive piece cur))]
        (or status (recur (next pieces)))))))

(defn- run-format
  "Runs the compiled format ready over arguments on *out*, inside a pretty
  print of its own when it needs one and is in none."
  [ready arguments]
  (let [go (fn []
             (let [cur (cursor arguments)]
               (binding [*lists-left* (fn [] (not (more? cur)))]
                 (walk (::pieces ready) cur)))
             nil)]
    (if (and (::pretty ready) (not (k/active?)))
      (k/call go *print-right-margin* *print-miser-width*)
      (go))))

(defn cl-format
  "Common Lisp's format: writes what the control string format-in makes of
  args on writer -- nil to answer the text as a string, true for *out*.
  format-in may also be a format compiled before."
  [writer format-in & args]
  (let [ready (as-format format-in)]
    (cond (nil? writer) (with-out-str (run-format ready args))
          (true? writer) (run-format ready args)
          :else (binding [*out* writer] (run-format ready args)))))

(defn- formatter-fn
  "The function formatter makes: cl-format with its control string fixed."
  [format-in]
  (let [ready (as-format format-in)]
    (fn [stream & args] (apply cl-format stream ready args))))

(defn- formatter-out-fn
  "The function formatter-out makes: the control string run over its
  arguments on *out* as it stands, a pretty print in progress included."
  [format-in]
  (let [ready (as-format format-in)]
    (fn [& args]
      (let [cur (cursor args)]
        (binding [*lists-left* (fn [] (not (more? cur)))]
          (walk (::pieces ready) cur)))
      nil)))

(defmacro formatter
  "A function of a stream and arguments that formats them by the control
  string format-in, compiled once."
  [format-in]
  `(#'formatter-fn ~format-in))

(defmacro formatter-out
  "A function of arguments that formats them by the control string
  format-in on *out*, for a dispatch function to call inside a pretty
  print."
  [format-in]
  `(#'formatter-out-fn ~format-in))

;; code-dispatch: Clojure code. A list is laid out by the symbol it begins
;; with. Each layout below was read off what clj prints for forms of its
;; kind at margins that force every break and at margins that force none;
;; where a layout is a run of directives it is written as a control string
;; of cl-format above.

(declare code-dispatch)

(defn- lay
  "Lays args out by the control string control, as part of the pretty
  print in progress."
  [control & args]
  (let [cur (cursor args)]
    (binding [*lists-left* (fn [] (not (more? cur)))]
      (walk (::pieces (as-format control)) cur))
    nil))

(defn- plain-call
  "A call with nothing special about it: its members a blank and a linear
  newline apart, the lines after the first one column in."
  [form]
  (pprint-logical-block :prefix "(" :suffix ")"
    (pprint-indent :block 1)
    (write-members form)))

(defn- head-first
  "A form that keeps its first argument beside its head when it breaks and
  puts the rest right under the head: def, -> and their like."
  [form]
  (lay "~:<~w~^ ~@_~w~^ ~_~@{~w~^ ~_~}~:>" form))

(defn- test-and-branches
  "if and its like: the test beside the head, the branches under it."
  [form]
  (lay "~:<~1I~w~^ ~@_~w~@{ ~_~w~}~:>" form))

(defn- side-by-side
  "pairs, two by two: the two of a pair side by side (a miser newline
  between them), pair under pair."
  [pairs]
  (print-length-loop [pairs (seq pairs)]
    (when pairs
      (pprint-logical-block
        (write-out (first pairs))
        (when (next pairs)
          (print " ")
          (pprint-newline :miser)
          (write-out (second pairs))))
      (when (nnext pairs)
        (print " ")
        (pprint-newline :linear)
        (recur (nnext pairs))))))

(defn- bindings-and-body
  "let and its like: the binding vector beside the head, name and value
  side by side in it, and the body under the head. Without a vector to
  bind in, clj prints the form as a call inside one more pair of
  parentheses, and so does this."
  [form]
  (pprint-logical-block :prefix "(" :suffix ")"
    (if (and (next form) (vector? (second form)))
      (do (lay "~w ~1I~@_" (first form))
          (pprint-logical-block :prefix "[" :suffix "]" (side-by-side (second form)))
          (lay " ~_~{~w~^ ~_~}" (nnext form)))
      (plain-call form))))

(defn- definition
  "defn and its like: the name beside the head; then a docstring, a map of
  metadata, the parameters and the body, each on a line of its own once the
  form breaks -- but parameters that follow the name directly stay beside
  it."
  [form]
  (if (next form)
    (let [[head name & more] form
          doc (when (string? (first more)) (first more))
          more (if doc (next more) more)
          attrs (when (map? (first more)) (first more))
          more (if attrs (next more) more)]
      (pprint-logical-block :prefix "(" :suffix ")"
        (lay "~w ~1I~@_~w" head name)
        (when doc (lay " ~_~w" doc))
        (when attrs (lay " ~_~w" attrs))
        (if (and (vector? (first more)) (not doc) (not attrs))
          (apply lay " ~@_~w~@{ ~_~w~}" more)
          (lay "~{ ~_~w~}" more))))
    (plain-call form)))

(defn- clause-pairs
  "cond: test and value side by side, pair under pair, under the head."
  [form]
  (pprint-logical-block :prefix "(" :suffix ")"
    (pprint-indent :block 1)
    (write-out (first form))
    (when (next form)
      (print " ")
      (pprint-newline :linear)
      (side-by-side (next form)))))

(defn- chosen-pairs
  "condp: the predicate and the expression beside the head, then test and
  value side by side, pair under pair. With nothing to choose from it is a
  plain call."
  [form]
  (if (> (count form) 3)
    (pprint-logical-block :prefix "(" :suffix ")"
      (pprint-indent :block 1)
      (lay "~w ~@_~w ~@_~w ~_" (first form) (second form) (nth form 2))
      (side-by-side (drop 3 form)))
    (plain-call form)))

(def ^:private ^:dynamic *parameter-spellings*
  "Inside an anonymous function written #(...): its parameters and the %
  each is written as."
  {})

(defn- anonymous-function
  "(fn* [params] (call...)), what the reader makes of #(call...), written
  back that way: one parameter as %, several as %1 %2 ... by their place in
  the vector."
  [form]
  (if (vector? (second form))
    (let [params (second form)
          spellings (if (= 1 (count params))
                      {(first params) '%}
                      (zipmap params (map (fn [i] (symbol (str "%" i))) (iterate inc 1))))]
      (binding [*parameter-spellings* spellings]
        (pprint-logical-block :prefix "#(" :suffix ")"
          (write-members (seq (first (nnext form)))))))
    (plain-call form)))

(defn- brackets
  [coll]
  (if (vector? coll) ["[" "]"] ["(" ")"]))

(defn- filled
  "The members of coll in its own brackets, as many to a line as fit."
  [coll]
  (let [[open close] (brackets coll)]
    (pprint-logical-block :prefix open :suffix close
      (print-length-loop [members (seq coll)]
        (when members
          (write-out (first members))
          (when (next members)
            (print " ")
            (pprint-newline :fill)
            (recur (next members))))))))

(defn- library-spec
  "A list or vector among the arguments of an ns reference. [lib :as x] and
  its like, three members with a keyword in the middle, stay on one line
  (a list or vector in the third place filled); any other keeps its first
  two members together and fills the rest in under the second."
  [spec]
  (let [[open close] (brackets spec)]
    (pprint-logical-block :prefix open :suffix close
      (if (and (= 3 (count spec)) (keyword? (second spec)))
        (let [[lib option value] spec]
          (lay "~w ~w " lib option)
          (if (or (seq? value) (vector? value)) (filled value) (write-out value)))
        (apply lay "~w ~:i~@{~w~^ ~:_~}" spec)))))

(defn- reference
  "A reference of an ns form, (:require ...) and its like: each argument a
  blank after the keyword or after the newline ahead of it, so that the
  arguments line up; that newline is linear after a list or a vector, which
  then gets a line of its own once the reference breaks, and fill after
  anything else."
  [form]
  (let [[open close] (brackets form)]
    (pprint-logical-block :prefix open :suffix close
      ;; a keyword *print-length* cut leaves the indentation where it was
      (when-not (write-out (first form))
        (pprint-indent :current 0))
      (loop [arguments (next form) ahead nil]
        (when arguments
          (when ahead (pprint-newline ahead))
          (print " ")
          (let [argument (first arguments)
                spec (or (seq? argument) (vector? argument))]
            (if spec (library-spec argument) (write-out argument))
            (recur (next arguments) (if spec :linear :fill))))))))

(defn- namespace-form
  "ns: the name beside the head; the docstring (written as it is, between
  quotes), a map of metadata and every reference each on a line of its
  own, always."
  [form]
  (if (next form)
    (let [[head name & more] form
          doc (when (string? (first more)) (first more))
          more (if doc (next more) more)
          attrs (when (map? (first more)) (first more))
          references (if attrs (next more) more)]
      (pprint-logical-block :prefix "(" :suffix ")"
        (lay "~w ~1I~@_~w" head name)
        (when (or doc attrs (seq references))
          (pprint-newline :mandatory))
        (when doc
          (print (str "\"" doc "\""))
          (when (or attrs (seq references))
            (pprint-newline :mandatory)))
        ;; a map *print-length* cut is not followed by its line end
        (when (and attrs (not (write-out attrs)) (seq references))
          (pprint-newline :mandatory))
        (loop [parts references]
          (when parts
            (let [part (first parts)]
              (if (or (seq? part) (vector? part)) (reference part) (write-out part))
              (when (next parts)
                (pprint-newline :linear)
                (recur (next parts))))))))
    (plain-call form)))

(def ^:private code-layouts
  "The layout of a list by the symbol it begins with."
  (let [by (fn [layout names] (zipmap names (repeat layout)))]
    (merge (by definition '[defn defn- defmacro fn])
           (by head-first '[def defonce . .. -> locking struct struct-map])
           (by bindings-and-body '[let loop binding with-open when-let if-let doseq dotimes when-first
                                   with-local-vars])
           (by test-and-branches '[if if-not when when-not])
           {'cond clause-pairs, 'condp chosen-pairs, 'ns namespace-form, 'fn* anonymous-function})))

(defn- code-list
  "A list of code: a reader mark for the lists the reader has one for,
  else the layout of its head -- a clojure.core head counting as the bare
  one, except for the special forms (def, if, fn*, .), which the compiler
  only knows bare -- else a plain call."
  [form]
  (when-not (write-marked form)
    (let [head (first form)
          layout (when (symbol? head)
                   (or (code-layouts head)
                       (when (and (= "clojure.core" (namespace head))
                                  (not (contains? '#{def if fn* .} (symbol (name head)))))
                         (code-layouts (symbol (name head))))))]
      (if layout (layout form) (plain-call form)))))

(defn- write-code
  "x as code-dispatch lays it out."
  [x]
  (cond
    (nil? x) (pr nil)
    (seq? x) (code-list x)
    (symbol? x) (let [spelling (get *parameter-spellings* x)]
                  (cond spelling (pr spelling)
                        *print-suppress-namespaces* (print (name x))
                        :else (pr x)))
    :else (write-data x)))

(defmulti code-dispatch
  "The dispatch function of code, a multimethod on the value's class: a
  list is laid out as the form its first member makes it -- a definition,
  a binding form, a conditional, a threading form, an ns form, a plain
  call -- and everything else as simple-dispatch lays it out."
  class)

(defmethod code-dispatch nil [_] (pr nil))
(defmethod code-dispatch clojure.lang.IPersistentList [x] (write-code x))
(defmethod code-dispatch clojure.lang.Symbol [x] (write-code x))
(defmethod code-dispatch clojure.lang.IPersistentVector [x] (write-data x))
(defmethod code-dispatch clojure.lang.IPersistentMap [x] (write-data x))
(defmethod code-dispatch clojure.lang.IPersistentSet [x] (write-data x))
(defmethod code-dispatch java.lang.Number [x] (write-data x))
(defmethod code-dispatch java.lang.String [x] (write-data x))
(defmethod code-dispatch clojure.lang.Keyword [x] (write-data x))
(defmethod code-dispatch java.lang.Boolean [x] (write-data x))
(defmethod code-dispatch java.lang.Character [x] (write-data x))
(defmethod code-dispatch :default [x] (write-code x))

;; Printing a value.

(defn- based?
  "Whether numbers are to be written in *print-base* and *print-radix*:
  one of the two is not as it starts out, here or in a print around this
  one."
  []
  (or *numbers-in-base* (not= *print-base* 10) (boolean *print-radix*)))

(defn pprint
  "Pretty prints object on *out*, or on writer, and ends the line: laid
  out through *print-pprint-dispatch* within *print-right-margin*."
  ([object] (pprint object *out*))
  ([object writer]
   (binding [*out* writer
             *print-pretty* true
             *numbers-in-base* (based?)]
     (k/call (fn [] (write-out object)) *print-right-margin* *print-miser-width*)
     (k/fresh-line))
   nil))

(defmacro pp
  "Pretty prints the last value the REPL answered, *1."
  []
  `(pprint *1))

(def ^:private write-options
  "The var each option of write binds for the duration of the call."
  {:base #'*print-base*
   :dispatch #'*print-pprint-dispatch*
   :length #'*print-length*
   :level #'*print-level*
   :miser-width #'*print-miser-width*
   :pretty #'*print-pretty*
   :radix #'*print-radix*
   :readably #'*print-readably*
   :right-margin #'*print-right-margin*
   :suppress-namespaces #'*print-suppress-namespaces*})

(defn write
  "Writes object as the options say and the printer's vars otherwise, with
  no line end after it. The options, each naming the var it stands in for:
  :base, :dispatch, :length, :level, :miser-width, :pretty, :radix,
  :readably, :right-margin and :suppress-namespaces; and :stream, the
  writer to write on -- true (the default) for *out*, nil to answer the
  text as a string."
  [object & {:as options}]
  (let [stream (get options :stream true)
        run (fn []
              (binding [*print-base* (get options :base *print-base*)
                        *print-pprint-dispatch* (get options :dispatch *print-pprint-dispatch*)
                        *print-length* (get options :length *print-length*)
                        *print-level* (get options :level *print-level*)
                        *print-miser-width* (get options :miser-width *print-miser-width*)
                        *print-pretty* (get options :pretty *print-pretty*)
                        *print-radix* (get options :radix *print-radix*)
                        *print-readably* (get options :readably *print-readably*)
                        *print-right-margin* (get options :right-margin *print-right-margin*)
                        *print-suppress-namespaces* (get options :suppress-namespaces
                                                         *print-suppress-namespaces*)]
                (if *print-pretty*
                  (binding [*numbers-in-base* (based?)]
                    (k/call (fn [] (write-out object)) *print-right-margin* *print-miser-width*))
                  (pr object))))]
    (cond (nil? stream) (with-out-str (run))
          (true? stream) (do (run) nil)
          :else (binding [*out* stream] (run) nil))))

(defmacro with-pprint-dispatch
  "Runs body with function as the pretty printer's dispatch function."
  [function & body]
  `(binding [*print-pprint-dispatch* ~function] ~@body))

(defn set-pprint-dispatch
  "Makes function the pretty printer's dispatch function from here on."
  [function]
  (alter-var-root #'*print-pprint-dispatch* (constantly function))
  nil)

(defn print-table
  "Prints the maps rows as a table with one column per key in ks (the keys
  of the first row when ks is left out): a blank line, the keys, a rule,
  then a line per row, every cell flush right in its column."
  ([rows] (print-table (keys (first rows)) rows))
  ([ks rows]
   (when (seq rows)
     (let [cell (fn [row key] (str (get row key)))
           widths (map (fn [key] (apply max (count (str key)) (map (fn [row] (count (cell row key))) rows)))
                       ks)
           flush-right (fn [text width] (str (k/padding (- width (count text)) \space) text))
           line (fn [open between close cells]
                  (str open (apply str (interpose between (map flush-right cells widths))) close))]
       (println)
       (println (line "| " " | " " |" (map str ks)))
       (println (line "|-" "-+-" "-|" (map (fn [width] (k/padding width \-)) widths)))
       (doseq [row rows]
         (println (line "| " " | " " |" (map (fn [key] (cell row key)) ks))))))))
