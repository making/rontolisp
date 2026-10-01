(defmulti mp class :default :everything-else) (defmethod mp :everything-else [_] "else!") (println (mp 1))
