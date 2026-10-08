;; A Ring application served by ring.adapter.rontolisp/run-server. One source
;; for every transport: a socket on the interpreter and the JVM, a Servlet
;; container under -o app.war, `wasmtime serve` under --component, and a host
;; calling the handle-request export under --no-wasi. The response builders
;; and the parameter middleware are the built-in ring.util / ring.middleware
;; namespaces.
(ns ring-hello
  (:require [ring.adapter.rontolisp :refer [run-server]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :as response]
            [clojure.string :as str]))

(defn hello [{:keys [query-string headers]}]
  (-> (response/response
       (str "Hello from Ring on rontolisp"
            (when query-string (str " (" query-string ")"))
            ", " (get headers "user-agent" "no user agent") "\n"))
      (response/content-type "text/plain")
      (response/charset "utf-8")))

(defn greet [{:keys [params]}]
  ;; /greet?name=Ann, or a urlencoded POST body name=Ann
  (-> (response/response (str "Hello, " (:name params "stranger") "!\n"))
      (response/content-type "text/plain")
      (response/charset "utf-8")))

(defn echo [{:keys [body]}]
  (-> (response/response (str/upper-case (if body (slurp body) "")))
      (response/content-type "text/plain")
      (response/charset "utf-8")))

(defn routes [{:keys [request-method uri] :as request}]
  (cond
    (= [request-method uri] [:get "/"]) (hello request)
    (= uri "/greet") (greet request)
    (= [request-method uri] [:post "/echo"]) (echo request)
    (= uri "/home") (response/redirect "/")
    :else (-> (response/not-found
               (str "no route for " (str/upper-case (name request-method)) " " uri "\n"))
              (response/content-type "text/plain"))))

(def handler
  (-> routes wrap-keyword-params wrap-params))

(run-server handler {:port 3000 :host "127.0.0.1"})
