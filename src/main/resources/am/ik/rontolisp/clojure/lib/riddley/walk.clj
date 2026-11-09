(ns riddley.walk
  "A code walker that knows the special forms and the locals they bind, built
  into rontolisp in place of riddley's namespace of the same name. Every macro
  call it meets expands with the locals in reach at the call as its &env, as the
  compiler expands it: macroexpand-all expands a whole form so, walk-exprs hands
  the expressions a predicate picks to a function. Written for this front end
  from riddley's documented behaviour."
  (:refer-clojure :exclude [macroexpand])
  (:require [riddley.compiler :as cmp]))

(defn macroexpand
  "x expanded while its head names a macro, the locals in reach as the macro's
  &env. A head naming a local, or one special-form? picks, stays as written. No
  function carries an inline expansion here, so a call stays a call."
  ([x]
   (macroexpand x nil))
  ([x special-form?]
   (cmp/with-base-env
     (loop [x x]
       (if (and (seq? x)
                (not (and special-form? (special-form? (first x))))
                (not (contains? (cmp/locals) (first x))))
         (let [expanded (macroexpand-1 x)]
           (if (identical? expanded x)
             x
             (recur expanded)))
         x)))))

(def ^:private specials
  "The special forms whose operands the walk tells apart: the compiler's."
  '#{def loop* recur if case* let* letfn* do fn* quote var clojure.core/import* . set! deftype*
     reify* try throw monitor-enter monitor-exit catch finally new &})

(defn- walk-all
  "Each form walked, realized while the locals of the scope are in reach."
  [f forms]
  (doall (map f forms)))

(defn- walk-arity
  "A fn arity, ([params] body...): its parameters in reach for its body."
  [f [params & body]]
  (cmp/with-lexical-scoping
    (doseq [param params]
      (cmp/register-arg param))
    (list* params (walk-all f body))))

(defn- walk-method
  "A reify* or deftype* method, (name [params] body...)."
  [f [method-name & arity]]
  (cons method-name (walk-arity f arity)))

(defn- do-form [f x]
  (list* 'do (walk-all f (rest x))))

(defn- def-form [f [_ n & more]]
  (cmp/with-lexical-scoping
    (cmp/register-local n '())
    (list* 'def (f n) (walk-all f more))))

(defn- fn-form [f x]
  (let [prelude (take-while (complement sequential?) x)
        arities (drop (count prelude) x)
        arities (if (vector? (first arities)) [arities] arities)]
    (cmp/with-lexical-scoping
      (when-let [named (second prelude)]
        (cmp/register-local named (list* 'fn* named (map #(take 1 %) arities))))
      (doall (concat prelude (map #(walk-arity f %) arities))))))

(defn- let-form
  "let*, loop* and letfn*: each binding in reach for the ones after it and for
  the body; a letfn*'s are in reach of every one of them."
  [f x recursive?]
  (cmp/with-lexical-scoping
    (let [pairs (partition-all 2 (second x))
          _ (when recursive?
              (doseq [[k] pairs]
                (cmp/register-local k nil)))
          bindings (loop [pairs pairs out []]
                     (if (seq pairs)
                       (let [[k v] (first pairs)
                             v (f v)]
                         (cmp/register-local k v)
                         (recur (rest pairs) (conj out k v)))
                       out))]
      (list* (first x) bindings (walk-all f (drop 2 x))))))

(defn- case-form [f [_ ge shift mask default imap switch-type check-type skip-check]]
  (let [m (into {} (map (fn [[k [test then]]] [k [test (f then)]]) imap))]
    (list 'case* ge shift mask (f default)
          (if (every? number? (keys m)) (into (sorted-map) m) m)
          switch-type check-type skip-check)))

(defn- try-form [f x]
  (list* 'try (doall (map #(f % :try-clause? true) (rest x)))))

(defn- catch-form [f [_ type local & body]]
  (cmp/with-lexical-scoping
    (when local
      (cmp/register-arg (vary-meta local assoc :tag type)))
    (list* 'catch type local (walk-all f body))))

(defn- reify-form [f [_ interfaces & methods]]
  (list* 'reify* interfaces (doall (map #(walk-method f %) methods))))

(defn- deftype-form [f [_ type class-name fields _ interfaces & methods]]
  (cmp/with-lexical-scoping
    (doseq [field fields]
      (cmp/register-arg field))
    (list* 'deftype* type class-name fields :implements interfaces
           (doall (map #(walk-method f %) methods)))))

(defn- dot-form [f [_ target member & args]]
  (list* '. (f target)
         (if (seq? member)
           (list* (first member) (walk-all f (rest member)))
           (f member))
         (walk-all f args)))

(defn- special-form-walk
  "The walk of a special form's operands, by its head."
  [head]
  (case head
    do do-form
    def def-form
    fn* fn-form
    (let* loop*) #(let-form %1 %2 false)
    letfn* #(let-form %1 %2 true)
    case* case-form
    try try-form
    catch catch-form
    reify* reify-form
    deftype* deftype-form
    . dot-form
    walk-all))

(defn- marked
  "A special form with its head marked for special-form?."
  [x]
  (list* (vary-meta (first x) assoc ::special true) (rest x)))

(defn walk-exprs
  "x walked as code, every macro call expanded: handler answers for each
  expression predicate picks, which the walk does not enter (handler walks it
  further if it means to), and every other one is rebuilt from its walked
  operands. A binding form's locals are in reach (riddley.compiler/locals) for
  the expressions they scope. A head special-form? picks is not expanded; catch
  and finally are special only in a try's clauses, which :try-clause? marks."
  ([predicate handler x]
   (walk-exprs predicate handler nil x))
  ([predicate handler special-form? x & {:keys [try-clause?]}]
   (cmp/with-base-env
     (let [x (macroexpand x special-form?)
           walk (partial walk-exprs predicate handler special-form?)
           walked (cond
                    (and (seq? x) (= 'var (first x)) (predicate x))
                    (handler (eval x))

                    (and (seq? x) (= 'quote (first x)) (not (predicate x)))
                    x

                    (predicate x)
                    (handler x)

                    (seq? x)
                    (let [head (first x)]
                      (if (or (not (contains? specials head))
                              (and (not try-clause?) (contains? '#{catch finally} head)))
                        (walk-all walk x)
                        ((special-form-walk head) walk (marked x))))

                    (or (set? x) (vector? x))
                    (into (empty x) (map walk x))

                    (record? x)
                    x

                    (map? x)
                    (into (empty x) (map walk x))

                    (and (symbol? x) (:test (meta x)))
                    (vary-meta x update :test walk)

                    :else
                    x)]
       (if (and (meta x) (instance? clojure.lang.IObj walked))
         (with-meta walked (merge (meta x) (meta walked)))
         walked)))))

(defn macroexpand-all
  "x with every macro call in it expanded, each among the locals in reach at the
  call."
  [x]
  (walk-exprs (constantly false) nil x))

(defn special-form?
  "Whether x is the head of a special form walk-exprs walked."
  [x]
  (when (symbol? x)
    (::special (meta x))))
