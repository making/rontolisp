;; portable.cljc -- one source for several Clojure platforms: reader conditionals pick a
;; branch per platform, and read-string can keep them as data instead.
;;
;;   rontolisp portable.cljc
;;   rontolisp portable.cljc -o Portable.class && java Portable
;;   rontolisp portable.cljc -o portable.wasm && wasmtime run portable.wasm
;;   rontolisp portable.cljc -o portable-c.wasm --component && wasmtime run portable-c.wasm
;;
;; rontolisp reads a .cljc file with the features :rontolisp and :clj. As in Clojure,
;; the first branch whose feature the reader has is taken, so a :rontolisp branch placed
;; before :clj is rontolisp's alone.
(ns portable
  (:require [clojure.string :as str]))

(defn platform []
  #?(:cljs "ClojureScript"
     :clj "a reader of :clj"
     :default "somewhere else"))

(defn reader []
  #?(:rontolisp "rontolisp"
     :clj "Clojure on the JVM"))

(defn now-ms []
  ;; both branches answer a number; the comparison below needs no clock
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

(def separators
  ;; #?@ splices a branch's elements into the enclosing form
  [\, #?@(:clj [\; \|] :cljs [\tab])])

(println (platform) "/" (reader))
(println (pos? (now-ms)) (pr-str separators))
(println (str/split "a,b;c|d" #"[,;|]"))

;; :read-cond :preserve reads a conditional as data, for a tool that rewrites source
(let [form (read-string {:read-cond :preserve} "#?(:clj [1 2] :cljs [3])")]
  (println (reader-conditional? form) (:form form) (:splicing? form) (pr-str form)))
(let [form (read-string {:read-cond :preserve} "[0 #?@(:clj [1 2])]")]
  (println (pr-str form) (:splicing? (second form))))
;; :allow picks a branch, as loading this file does
(println (read-string {:read-cond :allow} "#?(:cljs :web :clj :jvm)")
         (read-string {:read-cond :allow :features #{:cljs}} "#?(:cljs :web :clj :jvm)"))
;; a tagged literal no reader knows stays data too
(let [t (binding [*default-data-reader-fn* tagged-literal] (read-string "#color/rgb [255 0 0]"))]
  (println (tagged-literal? t) (:tag t) (:form t) (pr-str t)))
