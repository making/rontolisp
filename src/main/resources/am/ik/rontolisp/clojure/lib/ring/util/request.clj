(ns ring.util.request
  "Functions for augmenting and pulling information from request maps: the
  built-in ring.util.request of ring-core."
  (:require [clojure.string :as str]
            [rontolisp.internal.ring :as kernel]))

(defn request-url
  "Return the full URL of the request."
  [request]
  (str (-> request :scheme name)
       "://"
       (get-in request [:headers "host"])
       (:uri request)
       (when-let [query (:query-string request)]
         (str "?" query))))

(defn content-type
  "Return the content-type of the request, or nil if no content-type is set."
  [request]
  (when-let [type (get (:headers request) "content-type")]
    (if-let [i (str/index-of type ";")]
      (subs type 0 i)
      type)))

(defn content-length
  "Return the content-length of the request, or nil no content-length is set."
  [request]
  (when-let [length (get-in request [:headers "content-length"])]
    (kernel/parse-long length)))

(defn character-encoding
  "Return the character encoding for the request, or nil if it is not set."
  [request]
  (some-> (get-in request [:headers "content-type"])
          kernel/content-type-charset))

(defn urlencoded-form?
  "True if a request contains a urlencoded form in the body."
  [request]
  (when-let [type (content-type request)]
    (str/starts-with? type "application/x-www-form-urlencoded")))

(defn body-string
  "Return the request body as a string."
  [request]
  (let [body (:body request)]
    (cond
      (nil? body) nil
      (string? body) body
      (seq? body) (apply str body)
      :else (slurp body))))

(defn path-info
  "Returns the relative path of the request."
  [request]
  (or (:path-info request)
      (:uri request)))

(defn in-context?
  "Returns true if the URI of the request is a subpath of the supplied context."
  [request context]
  (str/starts-with? (:uri request) context))

(defn set-context
  "Associate a context and path-info with the request. The request URI must be
  a subpath of the supplied context."
  [request context]
  (assert (in-context? request context))
  (assoc request
         :context context
         :path-info (subs (:uri request) (count context))))
