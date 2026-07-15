;; A rontolisp port of wasmCloud's http-hello-world template
;; (templates/http-hello-world in the wasmCloud repo): the minimal HTTP
;; handler component.
;;
;;   GET /    -> "Hello from wasmCloud!"
;;   others   -> 404 "Not found"
;;
;; Run (interpreter, blocking server on :8080):
;;   rontolisp examples/wasmcloud/http-hello-world/app.lisp
;; Run (JVM class; running it needs the rontolisp jar on the classpath):
;;   rontolisp examples/wasmcloud/http-hello-world/app.lisp -o App.class && \
;;     java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. App
;; Run (WASI component under wasmtime serve):
;;   rontolisp examples/wasmcloud/http-hello-world/app.lisp -o app.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y app.wasm
;; Run (wasmCloud; .wash/config.yaml builds app.wasm and enables the gc proposal):
;;   wash dev    # in this directory; serves on :8000
;; Talk to it with:
;;   curl http://127.0.0.1:8080/

(defun text-response (status body)
  (list :status status
        :headers (list (cons "content-type" "text/plain"))
        :body body))

(defun home (request)
  (text-response 200 (format nil "Hello from wasmCloud!~%")))

(defun not-found (request)
  (text-response 404 (format nil "Not found~%")))

;; The request plist's :path carries the path only (any query string arrives
;; separately as :query), so the comparison is exact.
(defun handle (request)
  (if (string= (getf request :path) "/")
      (home request)
      (not-found request)))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
