(ns rontolisp.internal.string-joiner
  "java.util.StringJoiner where no host is, built into rontolisp: the joiner a
  (StringJoiner. delimiter [prefix suffix]) construction makes on wasm, where no
  host object exists. A deftype over the members added so far, so str, .toString
  and a member call read it through the methods below. Loaded where such a
  construction first lowers; where the host is the construction keeps the host's
  object. Written for this front end from the documented behaviour of the JDK
  class of the same name.")

(definterface Joiner
  (add [s])
  (setEmptyValue [s])
  (length []))

(defn- text
  "A CharSequence argument as the JDK reads it: nil is the string null."
  [s]
  (if (nil? s) "null" (str s)))

(defn- required
  "A constructor or setEmptyValue argument, refused when nil in the JDK's words."
  [s what]
  (if (nil? s)
    (throw (NullPointerException. ^String (str "The " what " must not be null")))
    (str s)))

(deftype StringJoiner [delimiter prefix suffix
                       ^:unsynchronized-mutable items
                       ^:unsynchronized-mutable empty-value]
  Joiner
  (add [this s]
    (set! items (conj items (text s)))
    this)
  (setEmptyValue [this s]
    (set! empty-value (required s "empty value"))
    this)
  (length [this]
    (count (str this)))
  Object
  (toString [_]
    (if (and (empty? items) (some? empty-value))
      empty-value
      (str prefix (apply str (interpose delimiter items)) suffix))))

(defn string-joiner
  "A joiner: (StringJoiner. delimiter) or (StringJoiner. delimiter prefix suffix)."
  ([delimiter] (string-joiner delimiter "" ""))
  ([delimiter prefix suffix]
   (StringJoiner. (required delimiter "delimiter") (required prefix "prefix")
                  (required suffix "suffix") [] nil)))
