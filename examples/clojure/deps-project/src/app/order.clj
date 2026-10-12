(ns app.order
  (:require [units.money :as money]))

(defn eur
  "The data reader of #app/eur \"12.50\": the amount in cents."
  [s]
  (money/cents s))

(defrecord LineItem [sku quantity price])

(defn line-item [sku quantity price]
  (->LineItem sku quantity price))

(defn subtotal [item]
  (* (:quantity item) (:price item)))

(defn total [items]
  (reduce + 0 (map subtotal items)))
