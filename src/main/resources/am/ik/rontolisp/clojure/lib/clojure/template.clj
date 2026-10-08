(ns clojure.template
  "Expression templates, built into rontolisp: substitute values for the
  argument symbols of an expression, once or once per group of values. Written
  for this front end from the documented behaviour of Clojure's namespace of the
  same name."
  (:require [clojure.walk :as walk]))

(defn apply-template
  "expr with each symbol of argv replaced, at any depth, by the value at the same
  position in values. Every member of argv must be a symbol."
  [argv expr values]
  (assert (every? symbol? argv))
  (walk/postwalk-replace (zipmap argv values) expr))

(defmacro do-template
  "A do block holding one copy of expr per group of values, each with the symbols
  of argv replaced by its group's values; values are cut into groups of argv's
  size and a short last group is dropped."
  [argv expr & values]
  (cons 'do (map #(apply-template argv expr %) (partition (count argv) values))))
