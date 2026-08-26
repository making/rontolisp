;; A rontolisp port of wasmCloud's http-hello-world template
;; (templates/http-hello-world in the wasmCloud repo): the minimal HTTP
;; handler component.
;;
;;   GET /    -> "Hello from wasmCloud!"
;;   others   -> 404 "Not found"
;;
;; Run (interpreter, blocking server on :8080):
;;   rontolisp examples/wasmcloud/http-hello-world/app.lisp
;; Run (JVM class; self-contained -- the server travels beside it):
;;   rontolisp examples/wasmcloud/http-hello-world/app.lisp -o App.class && \
;;     java -cp . App
;; Run (WASI component under wasmtime serve):
;;   rontolisp examples/wasmcloud/http-hello-world/app.lisp -o app.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y app.wasm
;; wasmCloud hosts it too: `wash dev` in this directory -- see ../README.md.
;; Talk to it with:
;;   curl http://127.0.0.1:8080/

(defun text-response (status body)
  (list status '(:content-type "text/plain") (list body)))

(defun home (env) (text-response 200 (format nil "Hello from wasmCloud!~%")))

(defun not-found (env) (text-response 404 (format nil "Not found~%")))

;; The env plist's :path-info carries the (percent-decoded) path only (any
;; query string arrives separately as :query-string), so the comparison is
;; exact.
(defun handle (env)
  (if (string= (getf env :path-info) "/") (home env) (not-found env)))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
