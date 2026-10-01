(defn with-out-str-as-fn [f] (let [s (new java.io.StringWriter)] (binding [*out* s] (f) (str s))))
(println (with-out-str-as-fn (fn [] (print "captured"))))
