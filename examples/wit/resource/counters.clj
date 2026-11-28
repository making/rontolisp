;;;; counters in Clojure -- the world counters.lisp implements, as vars.
;;;;
;;;; It implements the world `counters` of wit/counters.wit with ordinary
;;;; functions, each the var its member name names, as counters.lisp's defuns:
;;;;
;;;;   constructor(...)      counter-new      answers the counter: ANY value, here an atom
;;;;   add: func(...)        counter-add      a method takes that value first
;;;;   merge: static func    counter-merge
;;;;   (a handle released)   counter-drop     optional, called with the value
;;;;
;;;; A record crosses as a map, a result's error arm as an ex-info holding the error
;;;; under :rontolisp.wit/error.
;;;;
;;;;   rontolisp counters.clj -o counters.wasm --component
;;;;
;;;; app.lisp calls the two interfaces from another component -- see README.md.

(ns counters
  (:require [rontolisp.wit :as wit]))

;; The counters made and not yet released, for `live`.
(def alive (atom 0))

;; WIT: constructor(name: string, start: u32)
(defn counter-new [name start]
  (swap! alive inc)
  (atom {:name name :value start}))

;; WIT: add: func(by: u32) -> u32
(defn counter-add [self by]
  (:value (swap! self update :value + by)))

;; WIT: read: func() -> reading
(defn counter-read [self] @self)

;; WIT: merge: static func(a: counter, b: borrow<counter>) -> counter
;; `a` arrives owned and is changed and handed back; `b` is only lent.
(defn counter-merge [a b]
  (swap! a (fn [{:keys [name value]}]
             {:name (str name "+" (:name @b)) :value (+ value (:value @b))}))
  a)

;; Called with the value when a handle of it is released.
(defn counter-drop [_] (swap! alive dec))

;; WIT: total: func(counters: list<borrow<counter>>) -> u32
(defn total [counters] (reduce + (map (comp :value deref) counters)))

;; WIT: leader: func(counters: list<counter>) -> result<counter, string>
;; The leader goes back to the caller; the others stay here with no handle left.
(defn leader [counters]
  (when (empty? counters)
    (throw (ex-info "no counter to lead" {::wit/error "no counter to lead"})))
  (let [best (reduce (fn [a b] (if (> (:value @b) (:value @a)) b a)) counters)]
    (doseq [c counters]
      (when-not (identical? c best) (swap! alive dec)))
    best))

;; WIT: live: func() -> u32
(defn live [] @alive)

;; WIT (interface report): summary: func(c: borrow<counter>) -> string
(defn summary [c] (str (:name @c) " is at " (:value @c)))

(wit/export "wit/counters.wit" {:world "counters" :reactor true})
