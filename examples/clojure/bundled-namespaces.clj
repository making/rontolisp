;; bundled-namespaces.clj -- the namespaces clojure.jar ships, required like any other:
;; walk, edn, template, pprint (code-dispatch and cl-format), data, zip,
;; core.protocols, datafy, stacktrace, math, core.reducers, set, instant, repl, xml and
;; java.io.
;;
;;   rontolisp bundled-namespaces.clj
;;   rontolisp bundled-namespaces.clj -o Bundled.class && java Bundled
;;   rontolisp bundled-namespaces.clj -o bundled.wasm && wasmtime run --dir . bundled.wasm
;;   rontolisp bundled-namespaces.clj -o bundled-c.wasm --component && wasmtime run --dir . bundled-c.wasm
;;
;; clojure.java.io writes and removes notes.txt in the working directory, which a WASM
;; module reaches through --dir.
(ns bundled-namespaces
  (:require [clojure.walk :as walk]
            [clojure.edn :as edn]
            [clojure.template :as template]
            [clojure.pprint :as pp]
            [clojure.data :as data]
            [clojure.zip :as zip]
            [clojure.core.protocols :as p]
            [clojure.datafy :as d]
            [clojure.stacktrace :as st]
            [clojure.math :as math]
            [clojure.core.reducers :as r]
            [clojure.set :as set]
            [clojure.instant :as instant]
            [clojure.repl :as repl]
            [clojure.xml :as xml]
            [clojure.java.io :as io]))

(println "== clojure.walk")
(println (walk/postwalk #(if (number? %) (* 10 %) %) {:a [1 2 {:b 3}]}))
(println (walk/keywordize-keys {"name" "ada" "tags" [{"k" 1}]}))
(println (walk/postwalk-replace {:x 1 :y 2} '(+ :x (* :y :x))))

(println "== clojure.edn")
(let [config (edn/read-string "{:port 8080 :hosts [\"a\" \"b\"] :limits #{1 2} :ratio 3/4}")]
  (println (:port config) (:hosts config) (sort (:limits config)) (:ratio config)))
(println (edn/read-string {:readers {'cents (fn [n] (/ n 100))}} "#cents 1250"))
(println (try (edn/read-string "(+ 1 #=(+ 1 2))")
              (catch Exception e (ex-message e))))

(println "== clojure.template")
(template/do-template [label f] (println label (f 2 3)) "sum" + "product" *)

(println "== clojure.pprint")
(pp/pprint {:name "rontolisp" :backends [:interpreter :jvm :wasm :component]
            :nested {:depth 1 :items (range 12)}})
(pp/with-pprint-dispatch pp/code-dispatch
  (pp/pprint '(defn fib [n] (loop [a 0 b 1 i n] (if (zero? i) a (recur b (+ a b) (dec i)))))))
(pp/print-table [:lang :year] [{:lang "Lisp" :year 1958} {:lang "Clojure" :year 2007}])
(println (pp/cl-format nil "~a has ~d item~:p: ~{~a~^, ~}" "cart" 3 [:x :y :z]))
(println (pp/cl-format nil "~r / ~@r / ~:d / ~,2f" 42 2026 1234567 3.14159))

(println "== clojure.data")
(println (data/diff {:a 1 :b {:c 2 :d 3}} {:a 1 :b {:c 5 :d 3} :e 4}))

(println "== clojure.zip")
(let [z (zip/vector-zip [1 [2 3] [4 [5]]])]
  (println (-> z zip/down zip/right zip/down zip/node))
  (println (zip/root (zip/edit (-> z zip/down zip/right zip/down zip/right) * 10)))
  (println (loop [loc z acc []]
             (if (zip/end? loc) acc (recur (zip/next loc) (if (number? (zip/node loc)) (conj acc (zip/node loc)) acc))))))

(println "== clojure.core.protocols and clojure.datafy")
(defrecord Account [id owner])
(extend-protocol p/Datafiable
  Account
  (datafy [a] (with-meta {:account/id (:id a) :account/owner (:owner a)}
                {`p/nav (fn [_ k v] (if (= k :account/owner) {:user/name v :user/admin? true} v))})))
(let [data (d/datafy (->Account 7 "ada"))]
  (println data (d/nav data :account/owner (:account/owner data))))
(println (reduce + 0 (reify p/CollReduce (coll-reduce [_ f init] (f (f init 40) 2)))))

(println "== clojure.stacktrace and clojure.repl")
(let [failure (Exception. "outer" (ex-info "inner" {:code 42}))]
  (println (ex-message (st/root-cause failure)) (ex-data (repl/root-cause failure)))
  (st/print-throwable (st/root-cause failure))
  (println))
(defn greet "Says hello to someone." [who] (str "hello " who))
(repl/doc greet)

(println "== clojure.math")
(println (math/floor-div -7 2) (math/floor-mod -7 2) (math/round 2.5) (math/hypot 3 4)
         (math/to-degrees math/PI) (math/sqrt 2))

(println "== clojure.core.reducers")
(println (r/fold + (r/map inc (r/filter even? (vec (range 100))))))
(println (into [] (r/take 3 (r/mapcat (fn [x] [x x]) [1 2 3]))))

(println "== clojure.set")
(let [langs #{{:name "lisp" :year 1958} {:name "clojure" :year 2007}}]
  (println (sort (set/union #{1 2} #{2 3})) (set/difference #{1 2 3} #{2}) (set/intersection #{1 2} #{2 3}))
  (println (sort (map :name (set/select #(> (:year %) 2000) langs)))
           (set/rename-keys {:a 1 :b 2} {:a :x})
           (set/map-invert {:a 1 :b 2})))

(println "== clojure.instant")
(let [t (instant/read-instant-date "2026-10-12T09:30:00+09:00")]
  (println (pr-str t) (inst-ms t)))

(println "== clojure.xml")
(let [doc (xml/parse (java.io.ByteArrayInputStream.
                      (.getBytes "<order id='7'><item qty='2'>tea</item><item qty='1'>cake</item></order>" "UTF-8")))]
  (println (:tag doc) (:attrs doc) (map (juxt (comp :qty :attrs) (comp first :content)) (:content doc)))
  (xml/emit-element {:tag :total :attrs {:items "3"} :content ["ok"]}))

(println "== clojure.java.io")
(let [f (io/file "notes.txt")]
  (spit f "first line\n")
  (spit f "second line\n" :append true)
  (with-open [r (io/reader f)]
    (println (vec (line-seq r))))
  (with-open [w (io/writer f :append true)]
    (.write w "third line\n"))
  (println (.exists f) (.getName f) (count (slurp f)))
  (let [out (java.io.ByteArrayOutputStream.)]
    (io/copy f out)
    (println (count (.toByteArray out)) "bytes copied"))
  (io/delete-file f)
  (println (.exists f) (io/delete-file f :already-gone)))
