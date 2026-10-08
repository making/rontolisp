(ns clojure.stacktrace
  "Printing of throwables and their causes, built into rontolisp. Written for
  this front end from the documented behaviour of Clojure's namespace of the
  same name.")

(defn root-cause
  "The innermost cause of the throwable tr, following its causes; tr itself
  when it has none."
  [tr]
  (if-let [cause (ex-cause tr)]
    (recur cause)
    tr))

(defn print-trace-element
  "Prints the stack trace element e: a Clojure function as namespace/name, any
  other method as class.method, then its file and line."
  [e]
  (let [class-name (.getClassName e)
        method (.getMethodName e)
        fn-name (re-matches #"^([A-Za-z0-9_.-]+)\$(\w+)__\d+$" (str class-name))]
    (if (and fn-name (= "invoke" method))
      (print (str (nth fn-name 1) "/" (nth fn-name 2)))
      (print (str class-name "." method)))
    (print (str " (" (or (.getFileName e) "") ":" (.getLineNumber e) ")"))))

(defn print-throwable
  "Prints the class and message of the throwable tr, and its ex-data on a line
  of its own."
  [tr]
  (print (str (name (class tr)) ": " (let [message (ex-message tr)] (if (nil? message) "null" message))))
  (when-let [info (ex-data tr)]
    (newline)
    (pr info)))

(defn print-stack-trace
  "Prints the throwable tr and its stack trace, the first n frames when n is
  given."
  ([tr] (print-stack-trace tr nil))
  ([tr n]
   (let [frames (seq (.getStackTrace tr))]
     (print-throwable tr)
     (newline)
     (print " at ")
     (if-let [frame (first frames)]
       (print-trace-element frame)
       (print "[empty stack trace]"))
     (newline)
     (doseq [frame (if (nil? n) (rest frames) (take (dec n) (rest frames)))]
       (print "    ")
       (print-trace-element frame)
       (newline)))))

(defn print-cause-trace
  "Prints the throwable tr's stack trace and those of its causes, each cause
  after Caused by:, the first n frames of each when n is given."
  ([tr] (print-cause-trace tr nil))
  ([tr n]
   (print-stack-trace tr n)
   (when-let [cause (ex-cause tr)]
     (print "Caused by: ")
     (recur cause n))))

(defn e
  "Prints a short stack trace of the root cause of the last exception, *e."
  []
  (print-stack-trace (root-cause *e) 8))
