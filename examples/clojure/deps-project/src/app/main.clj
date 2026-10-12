(ns app.main
  "An order's invoice, priced in the currency the command line names."
  ;; this namespace defines its own format, so clojure.core's is left out
  (:refer-clojure :exclude [format])
  ;; units.money comes from the :local/root library; :rename spells a referred var
  ;; differently here
  (:require [units.money :as money :refer [render] :rename {render money->str}]
            [app.order :as order :refer [line-item]]
            ;; an alias for keywords only: app/audit.clj does not exist and is not loaded
            [app.audit :as-alias audit])
  ;; a no-op outside an ahead-of-time compile
  (:gen-class))

;; #app/eur is read by app.order/eur, which src/data_readers.clj maps it to
(def catalog
  {"tea"    (line-item "tea" 2 #app/eur "4.50")
   "cake"   (line-item "cake" 1 #app/eur "3.20")
   "coffee" (line-item "coffee" 3 #app/eur "2.80")})

(defn format [item currency]
  (str (:sku item) " x" (:quantity item) "  "
       (money->str (money/convert (order/subtotal item) currency) currency)))

(defn invoice [currency skus]
  (let [items (map catalog (or (seq skus) (keys catalog)))]
    (doseq [item items]
      (println (format item currency)))
    (println "total" (money->str (money/convert (order/total items) currency) currency))
    {::audit/event :invoiced ::audit/items (count items)}))

(defn -main
  "rontolisp -M:invoice [sku ...]: the :invoice alias passes EUR first."
  [currency & skus]
  (println "invoice in" currency)
  (prn (invoice currency skus)))

(defn summary
  "rontolisp -X:summary [:key value ...]: called with the alias's :exec-args, merged
  with the command line's."
  [{:keys [title currency]}]
  (println (str title ":")
           (money->str (money/convert (order/total (vals catalog)) currency) currency)))
