;; A rontolisp port of wasmCloud's http-client template
;; (templates/http-client in the wasmCloud repo): a served handler that makes
;; an outgoing HTTP request, i.e. rontolisp:fetch inside rontolisp:http-handler.
;; Every request is answered by proxying https://httpbin.org/get -- the
;; upstream status and body are forwarded as-is, and a failed upstream
;; request maps to 502.
;;
;; Run (interpreter, blocking server on :8080):
;;   rontolisp examples/wasmcloud/http-client/app.lisp
;; Run (JVM class; running it needs the rontolisp jar on the classpath):
;;   rontolisp examples/wasmcloud/http-client/app.lisp -o App.class && \
;;     java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. App
;; Run (WASI component under wasmtime serve; -S http=y grants outbound HTTP):
;;   rontolisp examples/wasmcloud/http-client/app.lisp -o app.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y -S http=y app.wasm
;; Run (wasmCloud; .wash/config.yaml builds app.wasm, enables the gc proposal
;; and allowlists the upstream -- wash denies outgoing HTTP by default):
;;   wash dev    # in this directory; serves on :8000
;; Talk to it with:
;;   curl http://127.0.0.1:8080/

;; The upstream to proxy, as in the original template.
(defun upstream-url () "https://httpbin.org/get")

;; A failed fetch surfaces as a nil/non-integer :status, mapped to 502 --
;; anything else (including upstream 4xx/5xx) is forwarded unchanged.
(defun handle (request)
  (let* ((res (rontolisp:await (rontolisp:fetch (upstream-url))))
         (status (getf res :status)))
    (if (integerp status)
        (list :status status
              :headers (list (cons "content-type" "application/json"))
              :body (getf res :body))
        (list :status 502
              :headers (list (cons "content-type" "text/plain"))
              :body (format nil "upstream request failed~%")))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
