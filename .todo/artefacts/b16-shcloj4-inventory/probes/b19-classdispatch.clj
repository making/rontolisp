(defmulti mm class) (defmethod mm String [s] (str "str:" s)) (defmethod mm nil [_] "was-nil") (defmethod mm :default [x] "dflt") (println (mm "a")) (println (mm nil)) (println (mm 1))
