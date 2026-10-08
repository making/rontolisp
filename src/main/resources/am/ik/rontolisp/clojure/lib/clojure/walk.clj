(ns clojure.walk
  "Traversal of nested Clojure data, built into rontolisp: walk rebuilds one
  level of a form, prewalk and postwalk every level, before or after its
  members. Written for this front end from the documented behaviour of
  Clojure's namespace of the same name.")

(defn- with-meta-of
  "rebuilt carrying the metadata of form, or rebuilt itself when form has none."
  [rebuilt form]
  (if-let [m (meta form)]
    (with-meta rebuilt m)
    rebuilt))

(defn walk
  "Calls inner on each member of form, rebuilds a value of form's own kind from
  the answers and returns what outer answers for it. A list stays a list and
  any other seq is realized; a record keeps its type and the keys beyond its
  fields; every other collection is poured into an empty one of its kind, so a
  sorted collection keeps its comparator; metadata is kept. A map's members are
  its [key value] entries. A value that is no collection goes to outer as is."
  [inner outer form]
  (outer
   (cond
     (list? form) (with-meta-of (map inner form) form)
     (seq? form) (with-meta-of (doall (map inner form)) form)
     (record? form) (with-meta-of (reduce (fn [rebuilt entry] (conj rebuilt (inner entry))) form form) form)
     (coll? form) (with-meta-of (into (empty form) (map inner form)) form)
     :else form)))

(defn postwalk
  "Walks form depth first, calling f on each value after its members have been
  replaced, and returns what f answers for the whole of form."
  [f form]
  (walk (fn [member] (postwalk f member)) f form))

(defn prewalk
  "Walks form depth first, calling f on each value before descending into what
  f answered for it."
  [f form]
  (walk (fn [member] (prewalk f member)) identity (f form)))

(defn postwalk-demo
  "Prints every value postwalk visits in form, as Walked: followed by its pr
  form, and returns form."
  [form]
  (postwalk (fn [x] (print "Walked: ") (prn x) x) form))

(defn prewalk-demo
  "Prints every value prewalk visits in form, as Walked: followed by its pr
  form, and returns form."
  [form]
  (prewalk (fn [x] (print "Walked: ") (prn x) x) form))

(defn keywordize-keys
  "Every map in m, at any depth, with its string keys made keywords. Each map
  becomes a hash map."
  [m]
  (postwalk (fn [x]
              (if (map? x)
                (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v]) x))
                x))
            m))

(defn stringify-keys
  "Every map in m, at any depth, with its keyword keys made strings (the name
  only, any namespace dropped). Each map becomes a hash map."
  [m]
  (postwalk (fn [x]
              (if (map? x)
                (into {} (map (fn [[k v]] [(if (keyword? k) (name k) k) v]) x))
                x))
            m))

(defn prewalk-replace
  "form with every value that is a key of smap replaced by smap's value for it,
  checked before its members are visited."
  [smap form]
  (prewalk (fn [x] (if (contains? smap x) (smap x) x)) form))

(defn postwalk-replace
  "form with every value that is a key of smap replaced by smap's value for it,
  checked after its members were visited."
  [smap form]
  (postwalk (fn [x] (if (contains? smap x) (smap x) x)) form))

(defn macroexpand-all
  "form with every seq in it macroexpanded, outermost first."
  [form]
  (prewalk (fn [x] (if (seq? x) (macroexpand x) x)) form))
