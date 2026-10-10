(ns clojure.lang
  "clojure.lang.PersistentQueue, built into rontolisp: the persistent FIFO queue
  a program reaches as clojure.lang.PersistentQueue/EMPTY. conj adds at the
  rear, peek and pop read and drop the front, seq walks front to rear. A deftype
  over the collection interfaces, so every core verb reads it through the
  methods below. Loaded where a program first names the class. Written for this
  front end from the documented behaviour of Clojure's class of the same name.")

(declare PersistentQueue-EMPTY)

(defn- hex
  "The unsigned hexadecimal digits of the int h, as Integer/toHexString spells
  them."
  [h]
  (loop [h (mod h 4294967296)
         digits ()]
    (let [digits (cons (nth "0123456789abcdef" (rem h 16)) digits)
          h (quot h 16)]
      (if (zero? h)
        (apply str digits)
        (recur h digits)))))

(defn- unsupported
  "The refusal of a java.util.Collection method that would change the queue."
  []
  (throw (UnsupportedOperationException.)))

;; f holds the front as a seq, r the members conjoined since f was last
;; refilled, as a vector (nil when there are none): conj appends to r, pop
;; drops f's head and, once f runs out, takes r's seq as the new front.
(deftype PersistentQueue [_meta cnt f r]
  clojure.lang.IPersistentList
  clojure.lang.IPersistentStack
  (peek [_] (first f))
  (pop [this]
    (if (nil? f)
      this
      (let [f1 (next f)]
        (if (nil? f1)
          (PersistentQueue. _meta (dec cnt) (seq r) nil)
          (PersistentQueue. _meta (dec cnt) f1 r)))))
  clojure.lang.IPersistentCollection
  (count [_] cnt)
  (cons [_ x]
    (if (nil? f)
      (PersistentQueue. _meta (inc cnt) (list x) nil)
      (PersistentQueue. _meta (inc cnt) f (conj (if (nil? r) [] r) x))))
  (empty [_] (with-meta PersistentQueue-EMPTY _meta))
  (equiv [this o]
    (and (sequential? o) (= (seq this) (seq o))))
  clojure.lang.Seqable
  (seq [_]
    (when f
      (if r (concat f r) f)))
  clojure.lang.Counted
  clojure.lang.Sequential
  clojure.lang.IHashEq
  (hasheq [this] (hash-ordered-coll this))
  clojure.lang.IObj
  (meta [_] _meta)
  (withMeta [this m]
    (if (identical? m _meta) this (PersistentQueue. m cnt f r)))
  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (seq this)))
  java.util.Collection
  (size [_] cnt)
  (isEmpty [_] (zero? cnt))
  (contains [this x] (if (some #(= % x) (seq this)) true false))
  (containsAll [this c] (every? #(.contains this %) c))
  (add [_ _] (unsupported))
  (addAll [_ _] (unsupported))
  (remove [_ _] (unsupported))
  (removeAll [_ _] (unsupported))
  (retainAll [_ _] (unsupported))
  (clear [_] (unsupported))
  java.io.Serializable
  Object
  (toString [this] (str "clojure.lang.PersistentQueue@" (hex (.hashCode this))))
  (hashCode [this]
    (if-let [s (seq this)] (.hashCode s) 1))
  (equals [this o]
    (and (sequential? o) (= (seq this) (seq o)))))

(def PersistentQueue-EMPTY
  "The empty queue: what clojure.lang.PersistentQueue/EMPTY reads."
  (PersistentQueue. nil 0 nil nil))
