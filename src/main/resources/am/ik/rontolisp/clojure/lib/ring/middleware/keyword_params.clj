(ns ring.middleware.keyword-params
  "Middleware that converts parameter keys in the request to keywords: the
  built-in ring.middleware.keyword-params of ring-core."
  (:require [rontolisp.internal.ring :as kernel]))

(defn- keyify-params [target parse-namespaces?]
  (cond
    (map? target)
    (into {}
          (for [[k v] target]
            [(if (and (string? k) (kernel/keyword-syntax? k parse-namespaces?))
               (keyword k)
               k)
             (keyify-params v parse-namespaces?)]))
    (vector? target)
    (vec (map #(keyify-params % parse-namespaces?) target))
    :else
    target))

(defn keyword-params-request
  "Converts string keys in :params map to keywords. See: wrap-keyword-params."
  ([request]
   (keyword-params-request request {}))
  ([request options]
   (update-in request [:params] keyify-params (:parse-namespaces? options false))))

(defn wrap-keyword-params
  "Middleware that converts the any string keys in the :params map to keywords.
  Only keys that can be turned into valid keywords are converted.

  This middleware does not alter the maps under :*-params keys. These are left
  as strings.

  Accepts the following options:

  :parse-namespaces? - if true, parse the parameters into namespaced keywords
                       (defaults to false)"
  ([handler]
   (wrap-keyword-params handler {}))
  ([handler options]
   (fn
     ([request]
      (handler (keyword-params-request request options)))
     ([request respond raise]
      (handler (keyword-params-request request options) respond raise)))))
