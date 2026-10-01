(println [(integer? 5) (number? 5) (keyword? :a) (map? {:a 1}) (set? #{1}) (list? (quote (1))) (fn? inc) (seq? [1]) (float? 1.0)])
