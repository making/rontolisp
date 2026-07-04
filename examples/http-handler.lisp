;; An HTTP handler function. The handler receives a request property list
;; (:method / :path / :headers / :body) and returns a response property list
;; (:status / :headers / :body). Unlike http-hello.lisp (a hand-rolled raw-TCP
;; server), rontolisp:http-handler adapts the request/response for you.
;;
;; Currently supported on the interpreter backend (a blocking server on :8080,
;; one virtual thread per request). The JVM backend and the WASI component
;; backend -- which compiles the handler into a wasi:http/incoming-handler
;; component runnable under `wasmtime serve` and Spin -- are in progress.
;;
;; Run (interpreter, blocking server on :8080):
;;   java -jar $JAR examples/http-handler.lisp
;; Talk to it with:  curl http://127.0.0.1:8080/hello

(defun handle (request)
  (list :status 200
        :headers (list (cons "content-type" "text/plain"))
        :body (format nil "Hello from rontolisp!~%~a ~a~%"
                      (getf request :method) (getf request :path))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
