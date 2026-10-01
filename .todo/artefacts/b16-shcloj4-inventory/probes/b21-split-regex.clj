(ns b21p (:require [clojure.string :as str])) (println (str/split "a,b;c" #"\W+"))
