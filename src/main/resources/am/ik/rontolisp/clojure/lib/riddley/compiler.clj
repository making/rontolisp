(ns riddley.compiler
  "The locals a code walk is among, built into rontolisp in place of riddley's
  namespace of the same name, whose own jar drives the Clojure compiler's
  internals. The locals in reach are the lowering's own: while a macro body runs
  they are its call's &env, which macroexpand-1 hands to every macro it expands,
  and riddley.walk grows them as it enters a binding form. Written for this front
  end from riddley's documented behaviour."
  (:require [rontolisp.internal.locals :as kernel]))

(defn tag-of
  "The symbol naming the class the :tag metadata of x names: nil without one,
  and for java.lang.Object."
  [x]
  (when-let [tag (:tag (meta x))]
    (let [sym (symbol (if (class? tag) (.getName ^Class tag) (name tag)))]
      (when-not (= 'java.lang.Object sym)
        sym))))

(defn locals
  "The locals in reach, the map &env is: each local's symbol to what &env holds
  for it (the symbol itself here). Outside a walk, nil where the program runs and
  in a macro body whose call sees no local."
  []
  (kernel/env))

(defmacro with-base-env
  "Runs body among the locals in reach, an empty map of them where there are
  none, so a walk outside a macro body registers its locals too."
  [& body]
  `(let [body# (fn [] ~@body)]
     (if (locals)
       (body#)
       (kernel/with-env {} body#))))

(defmacro with-lexical-scoping
  "Runs body in a scope of its own: the locals it registers are in reach until
  body returns."
  [& body]
  `(kernel/with-env (locals) (fn [] ~@body)))

(defmacro with-stub-vars
  "Runs body. riddley binds the compiler's own method and clearing vars around a
  registration here; this front end has none to bind."
  [& body]
  `(do ~@body))

(defn register-local
  "Registers the local v, bound to the form x, in the innermost scope; a symbol
  registered again takes the new one's place."
  [v x]
  (kernel/set-env! (assoc (dissoc (locals) v) v v)))

(defn register-arg
  "Registers the function parameter x in the innermost scope."
  [x]
  (kernel/set-env! (assoc (dissoc (locals) x) x x)))
