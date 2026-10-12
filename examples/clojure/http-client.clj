;; http-client.clj -- rontolisp.http-client (babashka.http-client's API over
;; rontolisp:fetch) talking to a Ring handler the same program serves on a free local
;; port, so it needs no outside host.
;;
;;   rontolisp http-client.clj
;;   rontolisp http-client.clj -o HttpClient.class && java HttpClient
;;
;; The client alone runs wherever fetch has a transport: a component (wasmtime run
;; -S http=y), a --no-wasi reactor with --host-fetch, --native. The free port comes from
;; java.net.ServerSocket and the server from a socket the program owns, which only the
;; interpreter and the JVM have.
(ns http-client
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ring.adapter.rontolisp :refer [run-server]]
            [ring.middleware.params :refer [wrap-params]]
            [rontolisp.http-client :as http]))

(defn handler [{:keys [request-method uri params form-params body headers]}]
  (case uri
    "/hello" {:status 200
              :headers {"content-type" "text/plain"}
              :body (str "hello " (get params "name" "stranger"))}
    "/echo" {:status 200
             :headers {"content-type" "text/plain" "x-method" (name request-method)}
             ;; wrap-params has read a form body into :form-params; any other is unread
             :body (str (get headers "content-type") " | "
                        (if (seq form-params) (pr-str (into (sorted-map) form-params)) (slurp body)))}
    "/lines" {:status 200
              :headers {"content-type" "application/x-ndjson"}
              :body (str/join (for [i (range 3)] (str "{\"n\":" i "}\n")))}
    {:status 404 :body "no such page"}))

(def port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))
(def base (str "http://127.0.0.1:" port))
(run-server (wrap-params handler) {:port port :host "127.0.0.1" :join? false})

;; a GET with query parameters
(let [r (http/get (str base "/hello") {:query-params {"name" "Ada Lovelace"}})]
  (println (:status r) (get-in r [:headers "content-type"]) "|" (:body r)))

;; a form POST and a raw body
(let [r (http/post (str base "/echo") {:form-params {"lang" "clojure" "n" "1"}})]
  (println (get-in r [:headers "x-method"]) "|" (:body r)))
(let [r (http/put (str base "/echo") {:headers {"content-type" "application/json"}
                                      :body "{\"id\": 7}"})]
  (println (get-in r [:headers "x-method"]) "|" (:body r)))

;; a reply read as a stream, a line at a time
(with-open [rdr (io/reader (:body (http/get (str base "/lines") {:as :stream})))]
  (println (vec (line-seq rdr))))

;; a status outside 2xx/3xx throws, with the response as the ex-data, unless :throw false
(println (try (http/get (str base "/missing"))
              (catch clojure.lang.ExceptionInfo e
                [(ex-message e) (:status (ex-data e)) (:body (ex-data e))])))
(println (:status (http/get (str base "/missing") {:throw false})))

;; :async answers a future of the response
(let [f (http/get (str base "/hello") {:async true :query-params {"name" "async"}})]
  (println (future? f) (:body @f)))

;; the server keeps the process alive; end it
(System/exit 0)
