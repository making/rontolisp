(ns clojure.stacktrace
  "A throwable, its frames and its causes as text, built into rontolisp.
  Written here from the documented behaviour of Clojure's namespace of the
  same name and from the lines it prints."
  (:require [rontolisp.internal.throwable :as t]))

(defn root-cause
  "The throwable at the end of tr's chain of causes: tr itself when nothing
  caused it."
  [tr]
  (loop [at tr]
    (let [cause (.getCause at)]
      (if cause (recur cause) at))))

(def ^:private fn-class
  "The class name the compiler gives a named function: its namespace, a
  dollar sign, its name, two underscores and a serial number."
  #"([\w.-]+)\$(\w+)__\d+")

(defn print-trace-element
  "Prints one stack frame without a line end: namespace/name for the invoke
  of a function's class, Class.method for any other frame, then the file and
  the line in parentheses."
  [e]
  (let [owner (.getClassName e)
        method (.getMethodName e)
        named (when (= method "invoke") (re-matches fn-class owner))]
    (print (if named
             (str (named 1) "/" (named 2))
             (str owner "." method)))
    (print (str " (" (.getFileName e) ":" (.getLineNumber e) ")"))))

(defn print-throwable
  "Prints the class of tr and its message without a line end, and for an
  ex-info its data on a second line."
  [tr]
  (print (str (t/class-name tr) ": " (if-some [m (.getMessage tr)] m "null")))
  (when-some [data (ex-data tr)]
    (newline)
    (pr data)))

(defn print-stack-trace
  "Prints tr like print-throwable, then its frames one per line, the first
  after at. With n, no more than n frames (but always the first)."
  ([tr] (print-stack-trace tr nil))
  ([tr n]
   (print-throwable tr)
   (newline)
   (print " at ")
   (let [frames (seq (.getStackTrace tr))]
     (if-not frames
       (print "[empty stack trace]")
       (do (print-trace-element (first frames))
           (doseq [frame (if n (take (dec n) (rest frames)) (rest frames))]
             (newline)
             (print "    ")
             (print-trace-element frame)))))
   (newline)))

(defn print-cause-trace
  "Prints the stack trace of tr and then of each cause down its chain, every
  cause introduced by Caused by:. With n, no more than n frames of each."
  ([tr] (print-cause-trace tr nil))
  ([tr n]
   (loop [at tr]
     (print-stack-trace at n)
     (when-some [cause (.getCause at)]
       (print "Caused by: ")
       (recur cause)))))

(defn e
  "For the REPL: the first eight frames of what started the exception in *e."
  []
  (print-stack-trace (root-cause *e) 8))
