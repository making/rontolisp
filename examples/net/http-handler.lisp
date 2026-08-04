;; An HTTP handler function. The handler receives the Clack environment
;; property list (:request-method / :path-info / :query-string / :headers /
;; :raw-body / ...) and returns a Clack response list (status headers body),
;; where body is a list of strings. Unlike http-hello.lisp (a hand-rolled
;; raw-TCP server), rontolisp:http-handler adapts the request/response for you.
;;
;; Supported on the interpreter and JVM backends (a blocking server on :8080,
;; one virtual thread per request) and the WASI component backend (--component),
;; which compiles the handler into an async wasi:http/handler@0.3.0 component
;; served by wasmtime 46+ and hosted by wasmCloud (`wash dev`, wash 2.5.2+ --
;; see examples/wasmcloud/) and by Spin (the canary build,
;; https://github.com/spinframework/spin/releases/tag/canary -- see
;; http-handler/spin.toml). jco cannot run it yet.
;;
;; Run (interpreter, blocking server on :8080):
;;   java -jar $JAR examples/net/http-handler.lisp
;; Run (JVM class; it implements the embedded server's handler interface, so
;; keep the rontolisp jar on the classpath):
;;   java -jar $JAR examples/net/http-handler.lisp -o App.class && java -cp $JAR:. App
;; Run (WASI component under wasmtime serve):
;;   java -jar $JAR examples/net/http-handler.lisp -o app.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y app.wasm
;; Run (the same component under Spin, which owns the socket on :3000):
;;   cd examples/net/http-handler && spin build && spin up
;; Talk to it with:  curl http://127.0.0.1:8080/hello

;; :request-method is a keyword (:GET, :POST, ...); symbol-name turns it back
;; into the bare method name for the text body.
(defun handle (env)
  (list 200 '(:content-type "text/plain")
        (list (format nil "Hello from rontolisp!~%~a ~a~%"
                      (symbol-name (getf env :request-method))
                      (getf env :path-info)))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
