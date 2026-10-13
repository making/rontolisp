;; java-interop.clj -- Clojure values handed to Java and back: fns as Runnable, Callable
;; and Comparator (inside collections too), Comparator's default methods on a fn, reify
;; and deftype implementing Java interfaces, records as java.util.Maps, Java threads
;; updating an atom, and host exceptions caught by class. Interop runs on the
;; interpreter and the JVM; the WASM targets have no host.
;;
;;   rontolisp java-interop.clj
;;   rontolisp java-interop.clj -o JavaInterop.class && java JavaInterop
(ns java-interop
  (:import (java.util ArrayList Collections Comparator TreeMap)
           (java.util.concurrent Executors TimeUnit)))

(println "== fns as Java's functional interfaces")
(let [pool (Executors/newFixedThreadPool 4)]
  (try
    ;; a vector of fns is a Collection of Callables
    (println (mapv #(.get %) (.invokeAll pool [(fn [] (reduce + (range 10))) (fn [] :second)])))
    ;; swap! from several Java threads: no increment is lost
    (let [hits (atom 0)
          tasks (repeat 8 (fn [] (dotimes [_ 500] (swap! hits inc))))]
      (run! #(.get %) (.invokeAll pool tasks))
      (println "hits:" @hits))
    (finally
      (.shutdown pool)
      (.awaitTermination pool 10 TimeUnit/SECONDS))))

(let [t (Thread. (fn [] (println "  ran in" (.getName (Thread/currentThread)))) "worker-1")]
  (.start t)
  (.join t))

(println "== fns as Comparators")
(let [by-length (fn [a b] (compare (count a) (count b)))
      words (ArrayList. ["kiwi" "fig" "banana" "apple"])]
  (Collections/sort words by-length)
  (println (vec words))
  ;; Comparator's default methods, called on the fn itself
  (.sort words (.reversed by-length))
  (println (vec words))
  (.sort words (.thenComparing by-length (Comparator/naturalOrder)))
  (println (vec words))
  (println (.compare (.reversed by-length) "a" "bb")))
;; a fn where Java expects a java.util.function.Function
(let [words (ArrayList. ["ccc" "a" "bb"])]
  (.sort words (Comparator/comparing count))
  (println (vec words) (vec (-> [1 2 3] .stream (.map (fn [x] (* x x))) .toList))))

(let [tree (TreeMap. ^Comparator (fn [a b] (compare b a)))]
  (doseq [k [3 1 2]] (.put tree k (str "v" k)))
  (println (.firstKey tree) (vec (.keySet tree)) (.get tree 2)))

;; a reify of Comparator sorts Clojure collections too
(let [descending (reify Comparator (compare [_ a b] (compare b a)))]
  (println (sort descending [1 3 2]) (sorted-set-by descending 1 3 2)))

(println "== reify and deftype as Java objects")
(let [task (reify Runnable (run [_] (println "  a reified Runnable ran")))]
  (doto (Thread. ^Runnable task) (.start) (.join)))

(deftype Version [major minor]
  Comparable
  (compareTo [_ other] (compare [major minor] [(.-major ^Version other) (.-minor ^Version other)]))
  Object
  (toString [_] (str major "." minor)))

(let [versions (ArrayList. [(Version. 1 10) (Version. 1 2) (Version. 0 9)])]
  (Collections/sort versions)
  (println (mapv str versions) (str (Collections/max versions))))

(defrecord Point [x y])
(let [p (->Point 1 2)]
  ;; a record crosses as a java.util.Map
  (println (.get ^java.util.Map p :x) (.containsKey ^java.util.Map p :y) (.size ^java.util.Map p)))

(println "== Java collections in Clojure")
(let [m (doto (java.util.LinkedHashMap.) (.put "a" 1) (.put "b" 2))]
  (println (into {} m) (count m) (get m "b") (map key m)))
(println (seq (doto (java.util.ArrayDeque.) (.push 1) (.push 2))) (vec (.toArray [1 2 3])))
(println (.equals [1 2] (java.util.List/of 1 2)) (= [1 2] (java.util.ArrayList. [1 2])))

(println "== host exceptions")
(let [port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))]
  ;; the port was just closed, so nothing listens on it
  (println (try (java.net.Socket. "127.0.0.1" (int port))
                (catch java.net.ConnectException e (str "ConnectException: " (ex-message e))))))
(println (try (Long/parseLong "12x")
              (catch NumberFormatException e (ex-message e))))
(println (try (.get (ArrayList.) 3)
              (catch IndexOutOfBoundsException e (class e))))
