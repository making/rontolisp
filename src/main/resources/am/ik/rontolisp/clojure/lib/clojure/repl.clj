(ns clojure.repl
  "Utilities for the REPL, built into rontolisp: a var's documentation and a
  throwable's stack trace. Written for this front end from the documented
  behaviour of Clojure's namespace of the same name."
  (:require [clojure.main :as main]
            [clojure.string :as str]
            [rontolisp.internal.throwable :as t]))

(def ^:private special-forms
  "The special forms doc names without a var, with the clojure.org page of
  those documented outside the special forms page."
  '{. "java_interop#dot" def nil do nil if nil monitor-enter nil monitor-exit nil
    new "java_interop#new" quote nil recur nil set! "vars#set" throw nil try nil var nil})

(defn- print-doc
  "Prints the documentation the map m holds: the name, the forms or argument
  lists, the kind, the docstring."
  [{n :ns nm :name :keys [forms arglists special-form doc url macro spec] :as m}]
  (println "-------------------------")
  (println (or spec (str (when n (str n "/")) nm)))
  (doseq [f forms]
    (print "  ")
    (prn f))
  (when arglists
    (prn arglists))
  (cond
    special-form (println "Special Form")
    macro (println "Macro")
    spec (println "Spec"))
  (when doc
    (println " " doc))
  (when special-form
    (if (contains? m :url)
      (when url
        (println (str "\n  Please see http://clojure.org/" url)))
      (println (str "\n  Please see http://clojure.org/special_forms#" nm)))))

(defmacro doc
  "Prints the documentation of the var or special form name names: its
  argument lists and docstring as its definition gave them."
  [name]
  (let [target (get '{& fn catch try finally try} name name)]
    (cond
      (contains? special-forms target)
      (let [url (get special-forms target)]
        `(#'print-doc '~(cond-> {:name target :special-form true} url (assoc :url url))))

      (keyword? name)
      (throw (UnsupportedOperationException.
              (str "clojure.repl/doc of " name " reads a spec: clojure.spec.alpha is not built in")))

      (= target 'fn)
      `(#'print-doc (assoc (meta (var ~'fn)) :special-form true))

      :else
      `(#'print-doc (meta (var ~name))))))

(defn demunge
  "The Clojure spelling of the class name fn-name of a function, as a stack
  trace element names it."
  [fn-name]
  (main/demunge fn-name))

(defn root-cause
  "The innermost cause of the throwable t, following its causes; t itself
  when it has none."
  [t]
  (main/root-cause t))

(defn stack-element-str
  "The stack trace element el as text, a Clojure function by its demunged
  name."
  [el]
  (main/stack-element-str el))

(def ^:private compiler-phases
  #{:read-source :macro-syntax-check :macroexpansion :compile-syntax-check :compilation})

(def ^:private dispatch-frames #{"clojure.lang.RestFn" "clojure.lang.AFn"})

(defn- simple-name
  "The class name of the throwable e as Class.getSimpleName spells it."
  [e]
  (let [class-name (t/class-name e)
        segment (fn [s c] (if-let [i (str/last-index-of s c)] (subs s (inc i)) s))]
    (segment (segment class-name ".") "$")))

(defn pst
  "Prints the stack trace of the throwable e to *err*, depth frames of it (12
  when not given), then each cause's after Caused by:. Without a throwable,
  the root cause of the last exception, *e."
  ([] (pst 12))
  ([e-or-depth]
   (if (instance? Throwable e-or-depth)
     (pst e-or-depth 12)
     (when-let [e *e]
       (pst (root-cause e) e-or-depth))))
  ([e depth]
   (binding [*out* *err*]
     (when (contains? compiler-phases (:clojure.error/phase (ex-data e)))
       (println "Note: The following stack trace applies to the reader or compiler, your code was not executed."))
     (println (str (simple-name e) " " (ex-message e)
                   (when-let [info (ex-data e)] (str " " (pr-str info)))))
     (let [frames (.getStackTrace e)
           cause (ex-cause e)]
       (doseq [el (take depth (remove #(contains? dispatch-frames (.getClassName %)) frames))]
         (println (str \tab (stack-element-str el))))
       (when cause
         (println "Caused by:")
         (pst cause (min depth (+ 2 (- (count (.getStackTrace cause)) (count frames))))))))))
