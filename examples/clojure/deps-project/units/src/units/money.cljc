;; A library shared with a ClojureScript front end: one .cljc source, reader conditionals
;; where the platforms differ. rontolisp has the features :rontolisp and :clj, and like
;; Clojure takes the first branch whose feature it has: a :rontolisp branch placed before
;; :clj is rontolisp's alone, and Clojure on the JVM skips it.
(ns units.money
  (:require [clojure.string :as str]
            #?(:clj [clojure.math :as math])))

(def rates {"EUR" 1 "USD" 108/100 "JPY" 162})

(defn- parse-int [s]
  ;; parse-long is portable: it runs on the WASM targets, where Long/parseLong
  ;; (a host call) does not exist
  #?(:rontolisp (parse-long s)
     :clj (Long/parseLong s)
     :cljs (js/parseInt s 10)))

(defn cents
  "Parses \"12.50\" into 1250."
  [s]
  (let [[whole frac] (str/split s #"\.")]
    (+ (* 100 (parse-int whole))
       (if frac (parse-int (subs (str frac "0") 0 2)) 0))))

(defn convert
  "Converts an amount of cents from EUR to the currency."
  [amount currency]
  (let [x (* amount (get rates currency 1))]
    #?(:clj (math/round (double x))
       :cljs (js/Math.round x))))

(defn render [amount currency]
  (let [sign (if (neg? amount) "-" "")
        amount (abs amount)]
    (if (= currency "JPY")
      (str sign (quot amount 100) " " currency)
      (str sign (quot amount 100) "." (format "%02d" (rem amount 100)) " " currency))))
