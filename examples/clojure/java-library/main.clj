;; main.clj -- a Clojure program calling a Java library of its own, put on the program's
;; Java class path with --java-classpath (a directory of classes or a jar; --java-dep
;; group:artifact:version fetches one from Maven Central instead).
;;
;;   javac -d classes java/example/Slugger.java
;;   rontolisp main.clj --java-classpath classes
;;   rontolisp main.clj --java-classpath classes -o Main.class && java -cp .:classes Main
;;   rontolisp main.clj --java-classpath classes -o main.jar && java -jar main.jar
;;
;; A jar copies each class path entry into main-lib/ beside it and names it in its
;; manifest. Java interop runs on the interpreter and the JVM; the WASM targets refuse
;; --java-classpath.
(ns main
  (:import (example Slugger)))

(println (Slugger/slug "Crème Brûlée, 2 portions!"))
(println Slugger/DEFAULT_SEPARATOR)

(let [snake (Slugger. "_")]
  (println (.apply snake "Hello, World"))
  ;; a vector is a java.util.List, a fn the Predicate
  (println (vec (.applyAll snake ["Tea Time" "" "Café au lait"] (fn [s] (not (empty? s)))))))

(println (map #(Slugger/slug %) ["Ünïcode Ünïon" "  spaced   out  "]))
