(ns ring.middleware.params
  "Middleware to parse url-encoded parameters from the query string and request
  body: the built-in ring.middleware.params of ring-core."
  (:require [ring.util.codec :as codec]
            [ring.util.request :as req]))

(defn- parse-params [params encoding]
  (let [params (codec/form-decode-map params encoding)]
    (if (map? params) params {})))

(defn- assoc-param-map [req k v]
  (some-> req (assoc k (if-let [existing (get req k)]
                         (reduce-kv assoc existing v)
                         v))))

(defn assoc-query-params
  "Parse and assoc parameters from the query string with the request."
  [request encoding]
  (let [params (if-let [query-string (:query-string request)]
                 (parse-params query-string encoding)
                 {})]
    (-> request
        (assoc-param-map :query-params params)
        (assoc-param-map :params params))))

(defn assoc-form-params
  "Parse and assoc parameters from the request body with the request."
  [request encoding]
  (let [params (if-let [body (and (req/urlencoded-form? request)
                                  (:body request))]
                 (parse-params (slurp body :encoding encoding) encoding)
                 {})]
    (-> request
        (assoc-param-map :form-params params)
        (assoc-param-map :params params))))

(defn params-request
  "Adds parameters from the query string and the request body to the request
  map. See: wrap-params."
  ([request]
   (params-request request {}))
  ([request options]
   (let [encoding (or (:encoding options)
                      (req/character-encoding request)
                      "UTF-8")
         request (if (:form-params request)
                   request
                   (assoc-form-params request encoding))]
     (if (:query-params request)
       request
       (assoc-query-params request encoding)))))

(defn wrap-params
  "Middleware to parse urlencoded parameters from the query string and form
  body (if the request is a url-encoded form). Adds :query-params,
  :form-params and :params (all of them merged) to the request map."
  ([handler]
   (wrap-params handler {}))
  ([handler options]
   (fn
     ([request]
      (handler (params-request request options)))
     ([request respond raise]
      (handler (params-request request options) respond raise)))))
