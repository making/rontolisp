(defmacro chain [x form] (list (quote .) x form)) (println (chain "abc" (substring 1)))
