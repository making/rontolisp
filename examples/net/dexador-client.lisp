;; An HTTP client on dexador (https://github.com/fukamachi/dexador), the
;; client most Common Lisp code is written against, quickloaded UNPATCHED from
;; Quicklisp. It talks to examples/net/httpbin.lisp: a GET with a query string,
;; a POST of a form (an alist), a POST of a JSON string with its own
;; Content-Type, and a 404 caught as dexador's typed condition. Each request
;; answers the body, the status and the response headers (a hash table) as
;; three values; the JSON bodies are read with rontolisp:json-parse.
;;
;; The connections come from dexador's pool, so the second request onward
;; reuses the first one's socket (keep-alive).
;;
;; Start examples/net/httpbin.lisp first (interpreter or JVM, port 8080), then:
;; Run (interpreter):     java -jar $JAR examples/net/dexador-client.lisp
;; Run (JVM):             java -jar $JAR examples/net/dexador-client.lisp -o DexClient.class && \
;;                          java -cp . DexClient
;; Run (WASM component):  java -jar $JAR examples/net/dexador-client.lisp -o dex.wasm --component && \
;;                          wasmtime run -S tcp=y -S inherit-network=y -S tls=y dex.wasm
;; Run (native):          java -jar $JAR examples/net/dexador-client.lisp --native -o dex && ./dex
;; A plain Preview 1 .wasm compiles, and refuses at the first socket call,
;; naming --native. The component needs -S tls=y even over http:// (dexador
;; loads the TLS layer either way). The base URL is the first argument
;; (default below; after -- on the interpreter); a host name instead of an IPv4
;; literal needs -S allow-ip-name-lookup=y on the component.
(ql:quickload "dexador" :silent t)

(defvar *base*
  (or (first (uiop:command-line-arguments)) "http://127.0.0.1:8080"))

(defun url (path) (concatenate 'string *base* path))

;; httpbin answers a JSON object; json-parse makes it a string-keyed hash table.
(defun field (json &rest keys)
  (let ((value (rontolisp:json-parse json)))
    (dolist (key keys value) (setq value (gethash key value)))))

(multiple-value-bind (body status headers) (dex:get (url "/get?lang=lisp&n=1"))
  (format t "GET  ~a ~a args=~a/~a~%" status (gethash "content-type" headers)
          (field body "args" "lang") (field body "args" "n")))

(defvar *form* '(("name" . "rontolisp") ("city" . "Tōkyō")))

(multiple-value-bind (body status) (dex:post (url "/post") :content *form*)
  (format t "POST ~a data=~a~%" status (field body "data")))

(defvar *json* '(("Content-Type" . "application/json")))

(multiple-value-bind (body status)
    (dex:post (url "/post") :content "{\"answer\": 42}" :headers *json*)
  (format t "POST ~a json.answer=~a~%" status (field body "json" "answer")))

(handler-case (dex:get (url "/missing"))
  (dex:http-request-not-found (e)
    (format t "GET  ~a ~a~%" (dex:response-status e)
            (field (dex:response-body e) "error"))))
