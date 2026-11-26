;; macros.clj -- macro bodies that call the program's own functions, read its vars and
;; evaluate at expansion time; templates naming helpers; expansion at run time.
;;
;;   rontolisp macros.clj
;;   rontolisp macros.clj -o Macros.class && java Macros
;;   rontolisp macros.clj -o macros.wasm && wasmtime run macros.wasm
;;   rontolisp macros.clj -o macros-c.wasm --component && wasmtime run macros-c.wasm
(ns macros
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

;; A plain function, called by the macro below while the program compiles: a macro body
;; sees every definition above its call site, as Clojure loads a file form by form.
(defn title-of [sym]
  (->> (str/split (name sym) #"-")
       (map str/capitalize)
       (str/join " ")))

(defmacro defheading
  "Defines a zero-argument function answering its name as a title."
  [sym]
  `(defn ~sym [] ~(title-of sym)))

(defheading release-notes)
(defheading getting-started)
(println (release-notes) "/" (getting-started))

;; A var's value at expansion time: the table is read while the program compiles, and
;; the expansion holds the constant.
(def unit-factors {:km 1000 :m 1 :cm 1/100})

(defmacro meters [n unit]
  (let [factor (get unit-factors unit)]
    (when-not factor
      (throw (ex-info (str "unknown unit " unit) {:unit unit})))
    `(* ~n ~factor)))

(println "3 km =" (meters 3 :km) "m," "250 cm =" (meters 250 :cm) "m")

;; eval inside a macro body: the argument is computed once, at expansion time.
(defmacro at-compile-time [expr]
  (eval expr))

(println "primes below 30:" (at-compile-time (vec (filter (fn [n] (not-any? #(zero? (mod n %)) (range 2 n)))
                                                           (range 2 30)))))

;; A template naming a private helper: the expansion calls it where the macro is used.
(defn- clamp* [lo hi x]
  (max lo (min hi x)))

(defmacro clamped [lo hi & body]
  `(clamp* ~lo ~hi (do ~@body)))

(println "clamped:" (clamped 0 10 (+ 7 8)) (clamped 0 10 (- 3)))

;; Auto-gensyms keep the template's locals apart from the caller's.
(defmacro with-timing-label [label & body]
  `(let [result# (do ~@body)]
     (str ~label ": " result#)))

(let [result 41]
  (println (with-timing-label "answer" (inc result))))

;; &form is the call itself; &env holds the locals in reach of it.
(defmacro call-shape []
  `'~[(first &form) (count &form) (vec (sort (map name (keys &env))))])

(let [a 1 b 2]
  (println "shape:" (call-shape) (+ a b)))

;; A macro of several arities, recursive in its own expansion.
(defmacro my-and
  ([] true)
  ([x] x)
  ([x & more] `(let [v# ~x] (if v# (my-and ~@more) v#))))

(println "my-and:" (my-and) (my-and 1 2 3) (my-and 1 nil 3))

;; Expansion at run time resolves names in the macro's namespace.
(println (macroexpand-1 '(clamped 0 1 x)))
(println (macroexpand '(meters 2 :km)))
(println (walk/macroexpand-all '(clamped 0 (meters 1 :km) (my-and b))))
