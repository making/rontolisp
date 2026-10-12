(ns app.order-test
  (:require [clojure.test :refer [deftest is are testing]]
            [app.order :as order]
            [units.money :as money]))

(deftest totals
  (testing "a line's subtotal is quantity times price"
    (is (= 900 (order/subtotal (order/line-item "tea" 2 450)))))
  (is (= 0 (order/total [])))
  (is (= 1220 (order/total [(order/line-item "a" 2 450) (order/line-item "b" 1 320)]))))

(deftest money
  (are [text cents] (= cents (money/cents text))
    "4.50" 450
    "3.2" 320
    "7" 700)
  (is (= "12.20 EUR" (money/render 1220 "EUR")))
  (is (= "1976 JPY" (money/render (money/convert 1220 "JPY") "JPY"))))
