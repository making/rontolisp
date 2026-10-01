(ns i (:import [java.time Instant])) (defprotocol P (to-ms [s])) (extend-protocol P Instant (to-ms [i] (.toEpochMilli i))) (println "extended")
