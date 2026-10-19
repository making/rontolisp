;;;; Serves ONE HTML page over HTTP: every request answers with the page as
;;;; text/html -- the Clojure twin of net/serve-html.lisp. The page is a
;;;; resource named by a string literal, so it is found while the program
;;;; COMPILES and its contents travel inside the artifact (a WASM component
;;;; included): the program carries the page as data and needs NO runtime
;;;; capabilities -- no filesystem, no environment, no arguments. Pick the file
;;;; by placing it on the source path (default "page.html", beside this
;;;; source) and recompiling.
;;;;
;;;; The handler is the Ring shape served by ring.adapter.rontolisp/run-server
;;;; -- no socket code of our own, request parsing and framing are the
;;;; transport's, the same transport net/serve-html.lisp's Clack adapter calls.
;;;;
;;;; Run (interpreter):        java -jar $JAR examples/clojure/serve-html.clj
;;;; Run (JVM):                java -jar $JAR examples/clojure/serve-html.clj -o ServeHtml.class && java -cp .:$JAR ServeHtml
;;;; Run (WASM component):     java -jar $JAR examples/clojure/serve-html.clj -o serve-html.wasm --component && wasmtime serve serve-html.wasm
;;;; Talk to it with:          curl http://127.0.0.1:8080/   (or a browser)

(ns serve-html
  (:require [clojure.java.io :as io]
            [ring.adapter.rontolisp :refer [run-server]]))

(def page
  ;; Found at BUILD time: a string-literal resource's contents are embedded in
  ;; the artifact, so the artifact keeps NO runtime filesystem/environment.
  (slurp (io/resource "page.html")))

(defn handle [_env]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body page})

;; Blocks and serves on :8080 (interpreter / JVM); under --component the port
;; is ignored (wasmtime owns the socket) and the handler is served through the
;; wasi:http interface -- the component imports nothing beyond it.
(run-server handle {:port 8080})
