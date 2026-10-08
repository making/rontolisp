;; A Ring application served by ring.adapter.rontolisp/run-server. One source
;; for every transport: a socket on the interpreter and the JVM, a Servlet
;; container under -o app.war, `wasmtime serve` under --component, and a host
;; calling the handle-request export under --no-wasi.
(ns ring-hello
  (:require [ring.adapter.rontolisp :refer [run-server]]
            [clojure.string :as str]))

(defn hello [{:keys [query-string headers]}]
  {:status 200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (str "Hello from Ring on rontolisp"
              (when query-string (str " (" query-string ")"))
              ", " (get headers "user-agent" "no user agent") "\n")})

(defn echo [{:keys [body]}]
  {:status 200
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (str/upper-case (if body (slurp body) ""))})

(defn handler [{:keys [request-method uri] :as request}]
  (cond
    (= [request-method uri] [:get "/"]) (hello request)
    (= [request-method uri] [:post "/echo"]) (echo request)
    :else {:status 404
           :headers {"Content-Type" "text/plain"}
           :body (str "no route for " (str/upper-case (name request-method)) " " uri "\n")}))

(run-server handler {:port 3000 :host "127.0.0.1"})
