;; shop.clj -- a WIT interface whose functions take and answer rich types, bound with
;; rontolisp.wit/import: each WIT value crosses as its Clojure spelling -- an enum as a
;; keyword, flags as a set of keywords, a variant as a keyword or [:case payload], a
;; record as a map, an option as the value or nil, a tuple as a vector, a result
;; argument as [:ok v] or [:error e], and a result answer as its ok value or a thrown
;; ExceptionInfo carrying the error arm.
;;
;;   rontolisp shop.clj
;;   rontolisp shop.clj -o Shop.class && java Shop
;;
;; The program provides the interface itself, which the interpreter and the JVM bind;
;; a WASM build takes it from its host instead.
(ns shop
  (:require [rontolisp.wit :as wit]))

(wit/import "shipping.wit" {:interface "example:shipping/rates@0.1.0" :as rates})

(def stock {"tea" 10 "cake" 0})

(defn- quote-for [sku quantity destination extras]
  (cond
    (zero? (get stock sku 0))
    (throw (ex-info "out of stock" {::wit/error [:out-of-stock sku]}))

    (and (vector? destination) (= :home (first destination))
         (= "Nowhere" (:city (second destination))))
    (throw (ex-info "unreachable" {::wit/error :unreachable}))

    :else
    (let [[kind _] (if (vector? destination) destination [destination])
          base ({:home 500 :locker 300 :store 0} kind)
          cents (+ base (* 120 quantity)
                   (if (contains? extras :gift-wrap) 250 0)
                   (if (contains? extras :express) 900 0))]
      {:carrier (case kind :home (if (contains? extras :express) :courier :post) :locker :post :store :pickup)
       :cents cents
       :days (when-not (= kind :store) (if (contains? extras :express) 1 3))})))

;; The provider: a function of the member's name and its arguments, seeing and
;; answering Clojure values.
(wit/provide "example:shipping/rates@0.1.0"
             (fn [member & args]
               (case member
                 "quote" (apply quote-for args)
                 "settle" (let [[payment] args]
                            (if (vector? payment)
                              (let [[arm value] payment]
                                (case arm
                                  :ok (str "paid " value " cents")
                                  :error (str "declined: " value)))
                              (str "arm without payload: " payment)))
                 "weights" (mapv (fn [[sku n]] (* n (if (= sku "tea") 250 400))) (first args)))))

;; the caller's side
(println (rates/quote "tea" 2 [:home {:street "1 Main St" :city "Springfield"}] #{}))
(println (rates/quote "tea" 1 [:home {:street "2 High St" :city "Springfield"}] #{:express :gift-wrap}))
(println (rates/quote "tea" 3 [:locker "L-17"] #{:signature}))
(println (rates/quote "tea" 1 :store #{}))

;; the error arm of a result answer is thrown
(doseq [[sku destination] [["cake" :store] ["tea" [:home {:street "?" :city "Nowhere"}]]]]
  (println (try (rates/quote sku 1 destination #{})
                (catch clojure.lang.ExceptionInfo e
                  [(ex-message e) (::wit/error (ex-data e))]))))

;; a result as an argument
(println (rates/settle [:ok 1240]))
(println (rates/settle [:error "card declined"]))

;; a list of tuples in, a list of numbers out
(println (rates/weights [["tea" 2] ["cake" 1]]))
