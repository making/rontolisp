(def p (proxy [java.util.ArrayList] [] (toString [] "hi"))) (println "proxy")
