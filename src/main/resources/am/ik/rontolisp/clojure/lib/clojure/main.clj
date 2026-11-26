(ns clojure.main
  "How Clojure's REPL and script runner describe an error, and the small
  helpers around that, built into rontolisp. Written here from the documented
  behaviour of Clojure's namespace of the same name and from the answers it
  gives to throwable data of every phase. The REPL and the runner themselves
  are not here: no compiler runs at run time."
  (:require [clojure.string :as str]))

(def ^:private spellings
  "What a class name holds in place of each character a Java identifier
  cannot, with the character it stands for. Every named spelling starts and
  ends with an underscore, so none begins another; the bare underscore and the
  dollar sign come last."
  [["_SINGLEQUOTE_" "'"] ["_DOUBLEQUOTE_" "\""] ["_AMPERSAND_" "&"] ["_PERCENT_" "%"]
   ["_LBRACE_" "{"] ["_RBRACE_" "}"] ["_LBRACK_" "["] ["_RBRACK_" "]"] ["_BSLASH_" "\\"]
   ["_COLON_" ":"] ["_TILDE_" "~"] ["_CIRCA_" "@"] ["_SHARP_" "#"] ["_CARET_" "^"]
   ["_SLASH_" "/"] ["_QMARK_" "?"] ["_PLUS_" "+"] ["_BANG_" "!"] ["_STAR_" "*"]
   ["_BAR_" "|"] ["_GT_" ">"] ["_LT_" "<"] ["_EQ_" "="] ["_" "-"] ["$" "/"]])

(defn demunge
  "The Clojure spelling of fn-name, a function's class name as a stack trace
  shows it: read left to right, each spelling of a character becomes that
  character, an underscore a hyphen and a dollar sign a slash."
  [fn-name]
  (let [end (count fn-name)]
    (loop [at 0, out []]
      (if (= at end)
        (apply str out)
        (let [rest-of (subs fn-name at)
              hit (some (fn [pair] (when (str/starts-with? rest-of (pair 0)) pair)) spellings)]
          (if hit
            (recur (+ at (count (hit 0))) (conj out (hit 1)))
            (recur (inc at) (conj out (subs rest-of 0 1)))))))))

(defn root-cause
  "The throwable at the end of t's chain of causes: t itself when nothing
  caused it."
  [t]
  (loop [at t]
    (if-some [cause (ex-cause at)] (recur cause) at)))

(defn stack-element-str
  "A stack frame as text: a frame of Clojure source (a .clj or .cljc file, or
  code read from no file) by its demunged class name, any other frame as
  Class.method; then the file and the line in parentheses."
  [el]
  (let [file (.getFileName el)
        owner (.getClassName el)
        clojure-source (and file
                            (or (str/ends-with? file ".clj")
                                (str/ends-with? file ".cljc")
                                (= file "NO_SOURCE_FILE")))]
    (str (if clojure-source (demunge owner) (str owner "." (.getMethodName el)))
         " (" file ":" (.getLineNumber el) ")")))

(defn repl-prompt
  "Prints the REPL's prompt: the current namespace and => ."
  []
  (print (str (ns-name *ns*) "=> ")))

(defn repl-exception
  "What the REPL reports for throwable: its root cause."
  [throwable]
  (root-cause throwable))

(defmacro with-read-known
  "Runs body with *read-eval* true where it is :unknown, and as it is
  otherwise."
  [& body]
  `(binding [*read-eval* (if (= :unknown *read-eval*) true *read-eval*)]
     ~@body))

(def repl-requires
  "The libspecs Clojure's REPL requires into user when it starts."
  '[[clojure.repl :refer (source apropos dir pst doc find-doc)]
    [clojure.java.javadoc :refer (javadoc)]
    [clojure.pprint :refer (pp pprint)]
    [clojure.repl.deps :refer (add-libs add-lib sync-deps)]])

;; ---------------------------------------------------------------------------
;; ex-triage: what went wrong, in which phase, where.

(def ^:private no-file #{"NO_SOURCE_FILE" "NO_SOURCE_PATH"})

(def ^:private core-namespaces
  "The namespaces whose functions an execution error is never blamed on: a
  frame of one is skipped when looking for where the program failed."
  #{"clojure.core" "clojure.core.protocols" "clojure.core.reducers" "clojure.data"
    "clojure.datafy" "clojure.edn" "clojure.instant" "clojure.java.io" "clojure.main"
    "clojure.pprint" "clojure.reflect" "clojure.repl" "clojure.set" "clojure.spec.alpha"
    "clojure.spec.gen.alpha" "clojure.spec.test.alpha" "clojure.string" "clojure.template"
    "clojure.uuid" "clojure.walk" "clojure.xml" "clojure.zip"})

(defn- frame-of-core?
  "True for a trace frame [class method file line] of Clojure's own runtime:
  a class of clojure.lang, or a function of a core namespace."
  [frame]
  (let [owner (when-some [c (first frame)] (name c))
        dollar (when owner (str/index-of owner "$"))]
    (boolean (and owner
                  (or (str/starts-with? owner "clojure.lang.")
                      (and dollar (contains? core-namespaces (subs owner 0 dollar))))))))

(defn- frame-symbol
  "The symbol a trace frame is reported by; nil when the frame names no class
  or no method. A function's invoke is its namespace and name, each nested
  function's name without the serial number the compiler appends; any other
  frame is Class/method."
  [frame]
  (let [owner (first frame)
        method (second frame)]
    (when (and owner method)
      (if (contains? '#{invoke invokeStatic} method)
        (let [parts (str/split (name owner) #"\$")
              unnumbered (fn [part]
                           (let [serial (str/index-of part "__")]
                             (if serial (subs part 0 serial) part)))
              names (map (comp demunge unnumbered) (rest parts))]
          ;; A class with no function part has no name to give: it prints as null.
          (symbol (demunge (first parts)) (if (seq names) (str/join "$" names) "null")))
        (symbol (name owner) (name method))))))

(defn- tidy-path
  "The path as a file system spells it: one slash between names, none at the
  end."
  [path]
  (let [single (str/replace path #"/+" "/")]
    (if (and (str/ends-with? single "/") (< 1 (count single)))
      (subs single 0 (dec (count single)))
      single)))

(defn- file-name [path]
  (let [slash (str/last-index-of path "/")]
    (if slash (subs path (inc slash)) path)))

(defn- with-file
  "The triage m with its source split into a path and the file name at its
  end; a source that names no file is dropped, with any path."
  [m]
  (let [source (:clojure.error/source m)]
    (cond (contains? no-file source)
          (dissoc m :clojure.error/source :clojure.error/path)

          (string? source)
          (let [path (tidy-path source)]
            (assoc m :clojure.error/source (file-name path) :clojure.error/path path))

          :else m)))

(defn- put
  "m with k set to v when v is neither nil nor false."
  [m k v]
  (if v (assoc m k v) m))

(defn- with-spec [m data]
  (put m :clojure.error/spec (when (:clojure.spec.alpha/problems data) data)))

(defn- with-frame
  "The triage m with what a trace frame says about where: its symbol, its
  file (file? decides whether a given file counts) and its line."
  [m frame file?]
  (let [file (get frame 2)]
    (-> m
        (put :clojure.error/symbol (frame-symbol frame))
        (put :clojure.error/source (when (file? file) file))
        (put :clojure.error/line (get frame 3)))))

(defn ex-triage
  "Sorts out an error from throwable data as Throwable->map answers it. The
  answer is a map of :clojure.error/ keys: always :phase (the data's :phase,
  :execution without one), and where known :class and :cause (the class and
  message of the innermost throwable), :symbol, :source, :path, :line and
  :column (what was being read, compiled, expanded or run, and where), and
  :spec (the data of a failed spec). A reader or compiler error takes its
  location from the data of the outermost throwable; an error while running
  takes it from the first stack frame that is the program's own."
  [datafied]
  (let [phase (get datafied :phase :execution)
        via (:via datafied)
        trace (:trace datafied)
        outer (:data (first via))
        inner (last via)
        failure (fn [m] (-> m
                            (put :clojure.error/class (:type inner))
                            (put :clojure.error/cause (:message inner))))
        from-outer (fn [data] (with-file (assoc (or data {}) :clojure.error/phase phase)))]
    (case phase
      :read-source
      (put (from-outer (merge (:data (second via)) outer)) :clojure.error/cause (:message inner))

      (:compile-syntax-check :compilation :macro-syntax-check :macroexpansion)
      (with-spec (failure (from-outer outer)) (:data inner))

      (:read-eval-result :print-eval-result)
      (failure (with-frame (assoc (or outer {}) :clojure.error/phase phase)
                           (vec (first trace))
                           some?))

      :execution
      (-> {:clojure.error/phase phase, :clojure.error/class (:type inner)}
          (put :clojure.error/cause (:message inner))
          (with-frame (vec (first (remove frame-of-core? trace)))
                      (fn [file] (and file (not (contains? no-file file)))))
          (with-spec (:data inner))))))

;; ---------------------------------------------------------------------------
;; ex-str: the report of a triage.

(def ^:private unremarkable
  "The classes a report leaves out: they say nothing the cause does not."
  #{"Exception" "RuntimeException"})

(defn- class-note
  "The class of a triage in parentheses, by its simple name; nil for an
  unremarkable one."
  [triage]
  (let [named (:clojure.error/class triage)
        simple (if named (last (str/split (name named) #"\.")) "")]
    (when-not (contains? unremarkable simple)
      (str "(" simple ")"))))

(defn- place
  "Where a triage happened: path or file, line and, when known, column; the
  REPL's first line when nothing says otherwise."
  [triage]
  (str "("
       (or (:clojure.error/path triage) (:clojure.error/source triage) "REPL")
       ":"
       (or (:clojure.error/line triage) 1)
       (when-some [column (:clojure.error/column triage)]
         (when column (str ":" column)))
       ")."))

(defn- no-spec-report [triage]
  (when (:clojure.error/spec triage)
    (throw (UnsupportedOperationException.
            "clojure.main/ex-str of spec problems: clojure.spec.alpha is a library this clojure.main does not load"))))

(defn ex-str
  "The report of an error from its triage (see ex-triage): one line saying
  in which phase it happened, to what and where, then the cause on a line of
  its own."
  [triage]
  (let [phase (:clojure.error/phase triage)
        target (:clojure.error/symbol triage)
        target-word (when target (str target))
        note (class-note triage)
        at (place triage)
        words (case phase
                :read-source ["Syntax error reading source at" at]
                :macro-syntax-check (do (no-spec-report triage)
                                        ["Syntax error macroexpanding" target-word "at" at])
                :macroexpansion ["Unexpected error" note "macroexpanding" target-word "at" at]
                :compile-syntax-check ["Syntax error" note "compiling" target-word "at" at]
                :compilation ["Unexpected error" note "compiling" target-word "at" at]
                :read-eval-result ["Error reading eval result" note "at" (str (if (nil? target) "null" target)) at]
                :print-eval-result ["Error printing return value" note "at" (str (if (nil? target) "null" target)) at]
                :execution (do (no-spec-report triage)
                               ["Execution error" note "at" target-word at]))
        cause (:clojure.error/cause triage)]
    (str (str/join " " (remove nil? words)) "\n" (if (nil? cause) "null" cause) "\n")))

(defn err->msg
  "The report of the throwable e: ex-str of its triage."
  [e]
  (ex-str (ex-triage (Throwable->map e))))

(defn repl-caught
  "What the REPL does with a throwable it caught: prints its report to *err*."
  [e]
  (binding [*out* *err*]
    (print (err->msg e))
    (flush)))
