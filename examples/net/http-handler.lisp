;; An HTTP handler function. The handler receives a request property list
;; (:method / :path / :query / :headers / :body) and returns a response property list
;; (:status / :headers / :body). Unlike http-hello.lisp (a hand-rolled raw-TCP
;; server), rontolisp:http-handler adapts the request/response for you.
;;
;; Supported on the interpreter and JVM backends (a blocking server on :8080,
;; one virtual thread per request) and the WASI component backend (--component),
;; which compiles the handler into a plain WASI 0.2 wasi:http/incoming-handler
;; component: any wasi:http host with the wasm-GC proposal enabled can serve it
;; (wasmtime serve, jco, wasmCloud). Spin cannot run the component yet (its
;; wasmtime does not enable wasm-GC and has no flag to do so).
;;
;; Run (interpreter, blocking server on :8080):
;;   java -jar $JAR examples/net/http-handler.lisp
;; Run (JVM class; it implements the embedded server's handler interface, so
;; keep the rontolisp jar on the classpath):
;;   java -jar $JAR examples/net/http-handler.lisp -o App.class && java -cp $JAR:. App
;; Run (WASI component under wasmtime serve):
;;   java -jar $JAR examples/net/http-handler.lisp -o app.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y app.wasm
;; Or under jco (Node.js; wasm-GC is on by default in V8):
;;   npx @bytecodealliance/jco serve app.wasm --port 8080
;; Or under wasmCloud: `wash dev` / `wash host` with the gc proposal enabled
;; (dev.wasm_proposals: [gc] in .wash/config.yaml, or --wasm-proposal gc).
;; Talk to it with:  curl http://127.0.0.1:8080/hello

(defun handle (request)
  (list :status 200
        :headers (list (cons "content-type" "text/plain"))
        :body (format nil "Hello from rontolisp!~%~a ~a~%"
                      (getf request :method) (getf request :path))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
