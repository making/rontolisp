;; A rontolisp port of wasmCloud's http-client template
;; (templates/http-client in the wasmCloud repo): a served handler that makes
;; an outgoing HTTP request, i.e. rontolisp:fetch inside rontolisp:http-handler.
;; Every request is answered by proxying https://httpbin.ik.am/get -- the
;; upstream status and body are forwarded as-is, and a failed upstream
;; request maps to 502.
;;
;; Run (interpreter, blocking server on :8080):
;;   rontolisp examples/wasmcloud/http-client/app.lisp
;; Run (JVM class; running it needs the rontolisp jar on the classpath):
;;   rontolisp examples/wasmcloud/http-client/app.lisp -o App.class && \
;;     java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. App
;; Run (WASI component under wasmtime serve; the wasi:http/client import that
;; carries the outbound fetch is host-provided by default):
;;   rontolisp examples/wasmcloud/http-client/app.lisp -o app.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y app.wasm
;; wasmCloud hosts it too: `wash dev` in this directory -- see ../README.md.
;; Talk to it with:
;;   curl http://127.0.0.1:8080/

;; The upstream to proxy, as in the original template.
(defun upstream-url () "https://httpbin.ik.am/get")

;; A failed fetch surfaces as a nil/non-integer :status, mapped to 502 --
;; anything else (including upstream 4xx/5xx) is forwarded unchanged.
(rontolisp:async-defun handle (request)
  ;; awaiting needs an async-defun; the response :body is an asynchronous
  ;; stream on every backend, drained with read-all.
  (let* ((res (rontolisp:await (rontolisp:fetch (upstream-url))))
         (status (getf res :status))
         (body (rontolisp:await (rontolisp:read-all (getf res :body)))))
    (if (integerp status)
        (list :status status
              :headers (list (cons "content-type" "application/json"))
              :body body)
        (list :status 502
              :headers (list (cons "content-type" "text/plain"))
              :body (format nil "upstream request failed~%")))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
