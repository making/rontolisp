(ns clojure.main
  "The error reports of Clojure's REPL and script runner and the helpers they
  share, built into rontolisp. Written for this front end from the documented
  behaviour of Clojure's namespace of the same name. The REPL and the runner
  themselves are not built in: no compiler runs at run time."
  (:require [clojure.string :as str]))

(def ^:private munged
  "Each munged spelling of a character in a class name, the longest first, so
  that one never takes the head of another."
  [["_DOUBLEQUOTE_" "\""] ["_SINGLEQUOTE_" "'"] ["_AMPERSAND_" "&"] ["_PERCENT_" "%"]
   ["_LBRACE_" "{"] ["_RBRACE_" "}"] ["_LBRACK_" "["] ["_RBRACK_" "]"] ["_BSLASH_" "\\"]
   ["_COLON_" ":"] ["_TILDE_" "~"] ["_CIRCA_" "@"] ["_SHARP_" "#"] ["_CARET_" "^"]
   ["_SLASH_" "/"] ["_QMARK_" "?"] ["_PLUS_" "+"] ["_BANG_" "!"] ["_STAR_" "*"]
   ["_BAR_" "|"] ["_GT_" ">"] ["_LT_" "<"] ["_EQ_" "="] ["_" "-"]])

(defn demunge
  "The Clojure spelling of the class name fn-name of a function, as a stack
  trace element names it: each munged character spelled back, $ as /."
  [fn-name]
  (loop [i 0 parts []]
    (if (>= i (count fn-name))
      (apply str parts)
      (let [c (nth fn-name i)]
        (cond
          (= c \$) (recur (inc i) (conj parts "/"))
          (= c \_) (let [tail (subs fn-name i)
                         [spelling character] (some (fn [entry] (when (str/starts-with? tail (first entry)) entry))
                                                    munged)]
                     (recur (+ i (count spelling)) (conj parts character)))
          :else (recur (inc i) (conj parts (str c))))))))

(defn root-cause
  "The innermost cause of the throwable t, following its causes; t itself
  when it has none."
  [t]
  (if-let [cause (ex-cause t)]
    (recur cause)
    t))

(defn stack-element-str
  "The stack trace element el as text: a Clojure function (a frame of a .clj
  or .cljc file) by its demunged class name, any other frame as
  class.method, then the file and line."
  [el]
  (let [file (.getFileName el)
        clojure? (and file (or (str/ends-with? file ".clj") (str/ends-with? file ".cljc")
                               (= file "NO_SOURCE_FILE")))]
    (str (if clojure?
           (demunge (.getClassName el))
           (str (.getClassName el) "." (.getMethodName el)))
         " (" file ":" (.getLineNumber el) ")")))

(defn repl-prompt
  "Prints the REPL's prompt: the current namespace and =>."
  []
  (print (str (ns-name *ns*) "=> ")))

(defn repl-exception
  "The root cause of the throwable."
  [throwable]
  (root-cause throwable))

(defmacro with-read-known
  "Evaluates body with *read-eval* true where it is :unknown."
  [& body]
  `(binding [*read-eval* (if (= :unknown *read-eval*) true *read-eval*)]
     ~@body))

(def repl-requires
  "The libspecs the oracle's REPL requires when it starts."
  '[[clojure.repl :refer (source apropos dir pst doc find-doc)]
    [clojure.java.javadoc :refer (javadoc)]
    [clojure.pprint :refer (pp pprint)]
    [clojure.repl.deps :refer (add-libs add-lib sync-deps)]])

(def ^:private unnamed-sources #{"NO_SOURCE_FILE" "NO_SOURCE_PATH"})

(def ^:private core-namespaces
  #{"clojure.core" "clojure.core.reducers" "clojure.core.protocols" "clojure.data" "clojure.datafy"
    "clojure.edn" "clojure.instant" "clojure.java.io" "clojure.main" "clojure.pprint" "clojure.reflect"
    "clojure.repl" "clojure.set" "clojure.spec.alpha" "clojure.spec.gen.alpha" "clojure.spec.test.alpha"
    "clojure.string" "clojure.template" "clojure.uuid" "clojure.walk" "clojure.xml" "clojure.zip"})

(defn- core-frame?
  "Whether the class named class-name is the language's own: clojure.lang, or
  a function of one of its namespaces."
  [class-name]
  (or (str/starts-with? class-name "clojure.lang.")
      (when-let [dollar (str/index-of class-name "$")]
        (contains? core-namespaces (subs class-name 0 dollar)))))

(defn- source-symbol
  "The symbol of the source a frame of the class clazz and the method method
  stands for: a Clojure function's namespace/name (its nested functions
  after $), else class/method."
  [clazz method]
  (if (contains? #{'invoke 'invokeStatic} method)
    (let [parts (map (fn [part]
                       (let [demunged (demunge part)]
                         (if-let [cut (str/index-of demunged "--")] (subs demunged 0 cut) demunged)))
                     (str/split (str clazz) #"\$"))]
      (symbol (first parts) (str/join "$" (rest parts))))
    (symbol (name clazz) (name method))))

(defn- file-name
  "The last segment of the path."
  [path]
  (let [slash (str/last-index-of path "/")]
    (if slash (subs path (inc slash)) path)))

(defn- with-source
  "The triage m with the file name and path of the source a reader or
  compiler error names, none for an unnamed source."
  [m source]
  (cond
    (contains? unnamed-sources source) (dissoc m :clojure.error/source :clojure.error/path)
    source (assoc m :clojure.error/source (file-name source) :clojure.error/path source)
    :else m))

(defn ex-triage
  "The phase, class, cause and location of an error, from the data of a
  throwable as Throwable->map answers it: a map of :clojure.error/phase
  (:execution unless the data names another) and, where known,
  :clojure.error/class, :clojure.error/cause, :clojure.error/symbol,
  :clojure.error/source, :clojure.error/path, :clojure.error/line,
  :clojure.error/column and :clojure.error/spec."
  [datafied-throwable]
  (let [phase (get datafied-throwable :phase :execution)
        via (:via datafied-throwable)
        trace (:trace datafied-throwable)
        {:keys [type message data]} (last via)
        problems (:clojure.spec.alpha/problems data)
        failing-fn (:clojure.spec.alpha/fn data)
        caller (:clojure.spec.test.alpha/caller data)
        top-data (:data (first via))
        source (:clojure.error/source top-data)]
    (assoc
     (case phase
       :read-source
       (cond-> (with-source (merge (:data (second via)) top-data) source)
         message (assoc :clojure.error/cause message))

       (:compile-syntax-check :compilation :macro-syntax-check :macroexpansion)
       (cond-> (with-source top-data source)
         type (assoc :clojure.error/class type)
         message (assoc :clojure.error/cause message)
         problems (assoc :clojure.error/spec data))

       (:read-eval-result :print-eval-result)
       (let [[clazz method file line] (first trace)]
         (cond-> top-data
           line (assoc :clojure.error/line line)
           file (assoc :clojure.error/source file)
           (and clazz method) (assoc :clojure.error/symbol (source-symbol clazz method))
           type (assoc :clojure.error/class type)
           message (assoc :clojure.error/cause message)))

       :execution
       (let [[clazz method file line] (first (drop-while #(core-frame? (name (first %))) trace))
             file (first (remove #(or (nil? %) (contains? unnamed-sources %)) [(:file caller) file]))
             line (or (:line caller) line)]
         (cond-> {:clojure.error/class type}
           line (assoc :clojure.error/line line)
           message (assoc :clojure.error/cause message)
           (or failing-fn (and clazz method)) (assoc :clojure.error/symbol
                                                     (or failing-fn (source-symbol clazz method)))
           file (assoc :clojure.error/source file)
           problems (assoc :clojure.error/spec data))))
     :clojure.error/phase phase)))

(defn- simple-class-name
  "The class name's last segment, as Class.getSimpleName spells a top-level
  class."
  [class-name]
  (let [dot (str/last-index-of class-name ".")]
    (if (and dot (< (inc dot) (count class-name)))
      (subs class-name (inc dot))
      class-name)))

(defn ex-str
  "The report of an error from its triage, as ex-triage answers it: a line
  naming the phase and the location, then the cause."
  [triage-data]
  (when (:clojure.error/spec triage-data)
    (throw (UnsupportedOperationException.
            "clojure.main/ex-str of spec problems: clojure.spec.alpha is not built in")))
  (let [{phase :clojure.error/phase source :clojure.error/source path :clojure.error/path
         line :clojure.error/line column :clojure.error/column symbol :clojure.error/symbol
         class :clojure.error/class cause :clojure.error/cause} triage-data
        loc (str (or path source "REPL") ":" (or line 1) (if column (str ":" column) ""))
        simple (when class (simple-class-name (name class)))
        cause-type (if (contains? #{"Exception" "RuntimeException"} simple) "" (str " (" simple ")"))
        at (if symbol (str symbol " ") "")]
    (case phase
      :read-source (format "Syntax error reading source at (%s).%n%s%n" loc cause)
      :macro-syntax-check (format "Syntax error macroexpanding %sat (%s).%n%s%n" at loc cause)
      :macroexpansion (format "Unexpected error%s macroexpanding %sat (%s).%n%s%n" cause-type at loc cause)
      :compile-syntax-check (format "Syntax error%s compiling %sat (%s).%n%s%n" cause-type at loc cause)
      :compilation (format "Unexpected error%s compiling %sat (%s).%n%s%n" cause-type at loc cause)
      :read-eval-result (format "Error reading eval result%s at %s (%s).%n%s%n" cause-type symbol loc cause)
      :print-eval-result (format "Error printing return value%s at %s (%s).%n%s%n" cause-type symbol loc cause)
      :execution (format "Execution error%s at %s(%s).%n%s%n" cause-type at loc cause))))

(defn err->msg
  "The report of the throwable e: ex-str of ex-triage of its data."
  [e]
  (-> e Throwable->map ex-triage ex-str))

(defn repl-caught
  "Prints the report of the throwable e to *err*, as the REPL does for an
  input that failed."
  [e]
  (binding [*out* *err*]
    (print (err->msg e))
    (flush)))
