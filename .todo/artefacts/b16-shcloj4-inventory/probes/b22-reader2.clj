(ns b22p (:require [clojure.java.io :as jio]))
(spit "/tmp/opencode/b16-probes/words.txt" "apple\nBanana\nfig\n")
(with-open [r (jio/reader "/tmp/opencode/b16-probes/words.txt")]
  (println (apply vector (line-seq r))))
