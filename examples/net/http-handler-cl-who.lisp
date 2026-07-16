;; An HTTP handler that renders its HTML response with cl-who, the real
;; upstream (X)HTML markup library loaded through asdf:load-system. It is the
;; cl-who counterpart of http-handler.lisp (a plain text/plain handler):
;; with-html-output-to-string expands cl-who's markup DSL at macro-expansion
;; time, so the template below compiles to ordinary string building; str / esc
;; splice the (escaped) request path in at run time.
;;
;; The library is loaded with asdf, so pass its directory with --system-path
;; (the sources are vendored under src/test/resources/cl-who); the compile
;; paths splice the system in at compile time, so the produced class /
;; component is self-contained. asdf:load-system scopes the loaded sources'
;; in-package to the load (like Common Lisp binding *package* around load), so
;; the handler defined below stays in cl-user and http-handler resolves it by
;; its (quoted) symbol.
;;
;; Supported on the interpreter and JVM backends (a blocking server on :8080,
;; one virtual thread per request) and the WASI component backend (--component),
;; which compiles the handler into an async wasi:http/handler@0.3.0 component
;; served by wasmtime 46+.
;;
;; Run (interpreter, blocking server on :8080):
;;   rontolisp examples/net/http-handler-cl-who.lisp --system-path src/test/resources/cl-who
;; Run (JVM class; keep the rontolisp jar on the classpath):
;;   rontolisp examples/net/http-handler-cl-who.lisp -o App.class --system-path src/test/resources/cl-who && \
;;     java -cp target/rontolisp-0.1.0-SNAPSHOT-exec.jar:. App
;; Run (WASI component under wasmtime serve):
;;   rontolisp examples/net/http-handler-cl-who.lisp -o app.wasm --component --system-path src/test/resources/cl-who && \
;;     wasmtime serve -W gc=y -W exceptions=y app.wasm
;; Talk to it with:  curl http://127.0.0.1:8080/world

(asdf:load-system :cl-who)

(defun handle (request)
  (let ((path (getf request :path)))
    (list :status 200
          :headers (list (cons "content-type" "text/html; charset=utf-8"))
          :body (cl-who:with-html-output-to-string (s)
                  (:html
                   (:head (:title "rontolisp + cl-who"))
                   (:body
                    (:h1 "Hello, World!")
                    (:p "You requested " (:code (cl-who:esc path)))))))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
