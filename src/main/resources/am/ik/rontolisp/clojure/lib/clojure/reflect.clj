(ns clojure.reflect
  "A class's bases, flags and members as data, built into rontolisp: each
  constructor, method and field a record of symbols and keyword flags. The
  interpreter and the JVM reflect through the host's java.lang.reflect; a
  WebAssembly target has no host class, so a reflection there throws
  UnsupportedOperationException. Written for this front end from the
  documented behaviour of Clojure's namespace of the same name."
  (:require [clojure.string :as str]
            [rontolisp.internal.reflect :as k]))

(defprotocol Reflector
  "What answers reflection data on a type."
  (do-reflect [reflector typeref]
    "The map of :bases, :flags and :members of the type typeref names."))

(defprotocol TypeReference
  "A name of a type: a class, or a symbol spelling an array type with one <>
  per dimension."
  (typename [o]
    "The type's name as Java spells it, an array type with one [] per
    dimension."))

(defprotocol ClassResolver
  "What finds the class file of a type."
  (resolve-class [this name]
    "The class file of the type name names, as an InputStream, or nil."))

(def flag-descriptors
  "The access flags reflection reports: each its keyword :name, its :flag bit
  and the :contexts (:class, :method, :field, :inner) the bit means it in."
  [{:name :public :flag 0x0001 :contexts #{:class :method :field}}
   {:name :private :flag 0x0002 :contexts #{:class :method :field}}
   {:name :protected :flag 0x0004 :contexts #{:class :method :field}}
   {:name :static :flag 0x0008 :contexts #{:method :field}}
   {:name :final :flag 0x0010 :contexts #{:class :method :field}}
   {:name :synchronized :flag 0x0020 :contexts #{:method}}
   {:name :volatile :flag 0x0040 :contexts #{:field}}
   {:name :bridge :flag 0x0040 :contexts #{:method}}
   {:name :varargs :flag 0x0080 :contexts #{:method}}
   {:name :transient :flag 0x0080 :contexts #{:field}}
   {:name :native :flag 0x0100 :contexts #{:method}}
   {:name :interface :flag 0x0200 :contexts #{:class}}
   {:name :abstract :flag 0x0400 :contexts #{:class :method}}
   {:name :strict :flag 0x0800 :contexts #{:method}}
   {:name :synthetic :flag 0x1000 :contexts #{:class :method :field}}
   {:name :annotation :flag 0x2000 :contexts #{:class}}
   {:name :enum :flag 0x4000 :contexts #{:class :field :inner}}])

(defrecord Constructor [name declaring-class parameter-types exception-types flags])

(defrecord Method [name return-type declaring-class parameter-types exception-types flags])

(defrecord Field [name type declaring-class flags])

(defn- no-host
  "The refusal of a reflection on a target without the host."
  [what]
  (throw (UnsupportedOperationException.
          (str "clojure.reflect/" what " reflects on a host class, which this target has not"))))

(extend-protocol TypeReference
  clojure.lang.Symbol
  (typename [s]
    (str/replace (str s) "<>" "[]"))

  Class
  (typename [c]
    ;; a target without the host (wasm) makes no Class
    (if (k/hosted?)
      (if (.isArray c)
        (str (typename (.getComponentType c)) "[]")
        (.getName c))
      (no-host "typename"))))

(extend-protocol ClassResolver
  ClassLoader
  (resolve-class [loader name]
    (if (k/hosted?)
      (.getResourceAsStream loader (str (str/replace (typename name) "." "/") ".class"))
      (no-host "resolve-class"))))

(defn- typesym
  "The symbol of the type t, an array type spelled with one <> per dimension."
  [t]
  (symbol (str/replace (typename t) "[]" "<>")))

(defn- parse-flags
  "The set of flag-descriptors names whose bit is set in flags and whose
  contexts hold context."
  [flags context]
  (reduce (fn [found {:keys [name flag contexts]}]
            (if (and (contains? contexts context) (not (zero? (bit-and flags flag))))
              (conj found name)
              found))
          #{}
          flag-descriptors))

(defn- types
  "The symbols of the classes cs, in a vector."
  [cs]
  (mapv typesym cs))

(defn- class-keyword?
  "Whether x is a class this front end spells as a keyword (Long, Double and
  the class of a number): one whose name holds a dot."
  [x]
  (and (keyword? x) (nil? (namespace x)) (str/includes? (name x) ".")))

(defn- host-reflect
  "The bases, flags and declared members of the host class of typeref, found
  through loader when typeref is no class."
  [typeref loader]
  ;; a target without the host (wasm) has no class to reflect on
  (if (k/hosted?)
    (let [typeref (if (class-keyword? typeref) (symbol (name typeref)) typeref)
          cls (if (class? typeref) typeref (Class/forName (typename typeref) false loader))
          super (.getSuperclass cls)
          bases (concat (when super [super]) (.getInterfaces cls))]
      {:bases (not-empty (set (map typesym bases)))
       :flags (parse-flags (.getModifiers cls) :class)
       :members (-> #{}
                    (into (map (fn [f]
                                 (->Field (symbol (.getName f)) (typesym (.getType f))
                                          (typesym (.getDeclaringClass f)) (parse-flags (.getModifiers f) :field))))
                          (.getDeclaredFields cls))
                    (into (map (fn [m]
                                 (->Method (symbol (.getName m)) (typesym (.getReturnType m))
                                           (typesym (.getDeclaringClass m)) (types (.getParameterTypes m))
                                           (types (.getExceptionTypes m)) (parse-flags (.getModifiers m) :method))))
                          (.getDeclaredMethods cls))
                    (into (map (fn [c]
                                 (->Constructor (symbol (.getName c)) (typesym (.getDeclaringClass c))
                                                (types (.getParameterTypes c)) (types (.getExceptionTypes c))
                                                (parse-flags (.getModifiers c) :method))))
                          (.getDeclaredConstructors cls)))})
    (no-host "do-reflect")))

(deftype JavaReflector [classloader]
  Reflector
  (do-reflect [_ typeref]
    (host-reflect typeref classloader)))

(def ^:private default-reflector
  (->JavaReflector (if (k/hosted?) (.getContextClassLoader (Thread/currentThread)) nil)))

(defn type-reflect
  "The map of :bases, :flags and :members (records of Constructor, Method and
  Field) of the type typeref names, a class or a symbol. Options: :ancestors
  true adds the symbols of every ancestor as :ancestors and their members to
  :members; :reflector, the Reflector to ask, a JavaReflector over the context
  class loader by default."
  [typeref & options]
  (let [{:keys [ancestors reflector]} (merge {:reflector default-reflector} (apply hash-map options))
        result (do-reflect reflector typeref)]
    (if-not ancestors
      result
      (loop [found {}
             pending (set (:bases result))]
        (if (seq pending)
          (let [found (into found (map (fn [t] [t (do-reflect reflector t)])) pending)]
            (recur found (set (remove #(contains? found %) (mapcat :bases (map found pending))))))
          (assoc result
                 :ancestors (set (keys found))
                 :members (reduce into (:members result) (map :members (vals found)))))))))

(defn reflect
  "type-reflect of obj when it is a class, else of its class; the options are
  type-reflect's."
  [obj & options]
  (apply type-reflect
         (cond
           (or (class? obj) (class-keyword? obj)) obj
           (nil? obj) nil
           :else (let [c (class obj)]
                   (if (class? c) c (symbol (k/class-name obj)))))
         options))
