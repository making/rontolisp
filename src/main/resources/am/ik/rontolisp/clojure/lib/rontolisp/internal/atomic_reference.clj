(ns rontolisp.internal.atomic-reference
  "java.util.concurrent.atomic.AtomicReference where no host is, built into
  rontolisp: the cell an (AtomicReference.) or (AtomicReference. x)
  construction makes on wasm, where no host object exists and no other thread
  races a write. A deftype over the value, so a member call reads it through
  the methods below; the comparing members compare by identity, like the
  JDK's. Loaded where such a construction first lowers; where the host is the
  construction keeps the host's object. Written for this front end from the
  documented behaviour of the JDK class of the same name.")

(definterface Reference
  (get [])
  (set [x])
  (lazySet [x])
  (getPlain [])
  (setPlain [x])
  (getAndSet [x])
  (compareAndSet [expected x])
  (weakCompareAndSetPlain [expected x])
  (getAndUpdate [f])
  (updateAndGet [f])
  (getAndAccumulate [x f])
  (accumulateAndGet [x f]))

(defn- applied
  "What the JDK's functional argument answers: a fn called, or the apply
  method of the UnaryOperator or BinaryOperator reified."
  ([f x] (if (fn? f) (f x) (.apply f x)))
  ([f x y] (if (fn? f) (f x y) (.apply f x y))))

(deftype AtomicReference [^:unsynchronized-mutable value]
  Reference
  (get [_] value)
  (set [_ x] (set! value x) nil)
  (lazySet [_ x] (set! value x) nil)
  (getPlain [_] value)
  (setPlain [_ x] (set! value x) nil)
  (getAndSet [_ x]
    (let [old value]
      (set! value x)
      old))
  (compareAndSet [_ expected x]
    (if (identical? value expected)
      (do (set! value x) true)
      false))
  (weakCompareAndSetPlain [_ expected x]
    (if (identical? value expected)
      (do (set! value x) true)
      false))
  (getAndUpdate [_ f]
    (let [old value]
      (set! value (applied f old))
      old))
  (updateAndGet [_ f]
    (set! value (applied f value))
    value)
  (getAndAccumulate [_ x f]
    (let [old value]
      (set! value (applied f old x))
      old))
  (accumulateAndGet [_ x f]
    (set! value (applied f value x))
    value)
  Object
  (toString [_] (if (nil? value) "null" (str value))))

(defn atomic-reference
  "A cell: (AtomicReference.) holding nil, or (AtomicReference. x)."
  ([] (AtomicReference. nil))
  ([x] (AtomicReference. x)))
