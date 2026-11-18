(ns clojure.repl
  "What one asks a REPL about a program, built into rontolisp: a var's
  documentation and a throwable's trace. Written here from the documented
  behaviour of Clojure's namespace of the same name and from the lines it
  prints."
  (:require [clojure.main :as main]
            [clojure.string :as str]
            [rontolisp.internal.throwable :as t]))

(def ^:private special-pages
  "The special forms doc knows, each with its page under clojure.org; the
  page is special_forms#name where none is given."
  '{def nil, do nil, if nil, quote nil, recur nil, throw nil, try nil, var nil,
    monitor-enter nil, monitor-exit nil,
    new "java_interop#new", . "java_interop#dot", set! "vars#set"})

(def ^:private documented-under
  "The words that are documented with the form they belong to."
  '{catch try, finally try})

(defn- print-doc
  "Prints what the metadata m says of a var or special form: a rule, the
  name, the forms and argument lists, what kind of thing it is, the
  docstring, and for a special form where to read more."
  [m]
  (println "-------------------------")
  (println (str (when-some [space (:ns m)] (str (ns-name space) "/")) (:name m)))
  (doseq [form (:forms m)]
    (println (str "  " form)))
  (when-some [arglists (:arglists m)]
    (prn arglists))
  (cond (:special-form m) (println "Special Form")
        (:macro m) (println "Macro"))
  (when-some [text (:doc m)]
    (println (str "  " text)))
  (when (:special-form m)
    (let [page (if (contains? m :url) (:url m) (str "special_forms#" (:name m)))]
      (when page
        (println)
        (println (str "  Please see http://clojure.org/" page))))))

(defmacro doc
  "Prints the documentation of the var or special form called name."
  [name]
  (let [name (get documented-under name name)]
    (cond (contains? special-pages name)
          (let [page (get special-pages name)]
            `(#'print-doc '~(cond-> {:name name, :special-form true} page (assoc :url page))))

          (contains? '#{fn &} name)
          `(#'print-doc (assoc (meta (var clojure.core/fn)) :special-form true))

          (keyword? name)
          (throw (UnsupportedOperationException.
                  (str "clojure.repl/doc of " name
                       " reads a spec: clojure.spec.alpha is a library this clojure.repl does not load")))

          :else
          `(#'print-doc (meta (var ~name))))))

(defn demunge
  "The Clojure spelling of fn-name, a function's class name as a stack trace
  shows it."
  [fn-name]
  (main/demunge fn-name))

(defn root-cause
  "The throwable at the end of t's chain of causes."
  [t]
  (main/root-cause t))

(defn stack-element-str
  "A stack frame as text, a Clojure function by its demunged name."
  [el]
  (main/stack-element-str el))

(def ^:private plumbing
  "The classes whose frames pst leaves out: every call of a function passes
  through them."
  #{"clojure.lang.AFn" "clojure.lang.RestFn"})

(def ^:private before-running
  "The phases in which an error is the reader's or the compiler's."
  #{:read-source :macro-syntax-check :macroexpansion :compile-syntax-check :compilation})

(defn- simple-name [class-name]
  (last (str/split class-name #"\.")))

(defn pst
  "Prints to *err* a throwable's simple class name, message and ex-data,
  then no more than depth of its frames (12 unless given), then the same
  for each cause after Caused by:. Without a throwable, prints the root
  cause of *e, and nothing when there is none."
  ([] (pst 12))
  ([e-or-depth]
   (if (instance? Throwable e-or-depth)
     (pst e-or-depth 12)
     (when-some [e *e]
       (pst (root-cause e) e-or-depth))))
  ([e depth]
   (binding [*out* *err*]
     (when (contains? before-running (:clojure.error/phase (ex-data e)))
       (println "Note: The following stack trace applies to the reader or compiler, your code was not executed."))
     (println (str (simple-name (t/class-name e)) " " (.getMessage e)
                   (when-some [data (ex-data e)] (str " " (pr-str data)))))
     (let [frames (.getStackTrace e)]
       (doseq [frame (take depth (remove (fn [el] (contains? plumbing (.getClassName el))) frames))]
         (println (str "\t" (stack-element-str frame))))
       (when-some [cause (.getCause e)]
         (println "Caused by:")
         ;; A cause repeats the frames of what it caused: show the ones it adds, and two more.
         (pst cause (min depth (+ 2 (- (count (.getStackTrace cause)) (count frames))))))))))
