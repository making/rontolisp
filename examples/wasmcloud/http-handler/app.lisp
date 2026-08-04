;; A rontolisp port of wasmCloud's http-handler template
;; (templates/http-handler in the wasmCloud repo): an HTTP handler with
;; routing -- the axum Router of the original becomes a plain cond over the
;; request path.
;;
;;   GET  /                      -> "Hello from wasmCloud!"
;;   GET  /api/greet?name=<name> -> "Hello, <name>!" ("world" when omitted)
;;   POST /api/echo              -> echoes the JSON body {"message": "..."}
;;   wrong method                -> 405 "Method Not Allowed"
;;   unknown path                -> 404 "Not found"
;;
;; Run (interpreter, blocking server on :8080):
;;   rontolisp examples/wasmcloud/http-handler/app.lisp
;; Run (JVM class; running it needs the rontolisp jar on the classpath):
;;   rontolisp examples/wasmcloud/http-handler/app.lisp -o App.class && \
;;     java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. App
;; Run (WASI component under wasmtime serve):
;;   rontolisp examples/wasmcloud/http-handler/app.lisp -o app.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y app.wasm
;; wasmCloud hosts it too: `wash dev` in this directory -- see ../README.md.
;; Talk to it with:
;;   curl http://127.0.0.1:8080/
;;   curl 'http://127.0.0.1:8080/api/greet?name=rontolisp'
;;   curl -X POST -d '{"message":"hi"}' http://127.0.0.1:8080/api/echo

;; --- request helpers --------------------------------------------------------

;; t when the body looks like a JSON object (json-parse signals on garbage;
;; the cheap guard answers 400 without wrapping the parse in handler-case).
(defun json-object-p (body)
  (and (stringp body) (> (length body) 0) (eql (char body 0) #\{)))

;; --- responses ---------------------------------------------------------------

(defun text-response (status body)
  (list status '(:content-type "text/plain") (list body)))

(defun json-response (status obj)
  (list status '(:content-type "application/json")
        (list (format nil "~a~%" (rontolisp:json-stringify obj)))))

;; --- handlers ----------------------------------------------------------------

(defun hello (env)
  (text-response 200 (format nil "Hello from wasmCloud!~%")))

;; The raw query string arrives as :query-string; rontolisp:query-param
;; url-decodes the value.
(defun greet (env)
  (let ((name (rontolisp:query-param (getf env :query-string) "name")))
    (text-response 200 (format nil "Hello, ~a!~%" (if name name "world")))))

(rontolisp:async-defun echo (env)
  ;; The env :raw-body is an asynchronous stream on every backend; drain it
  ;; first. The router stays a plain defun: the served dispatch awaits the
  ;; handler's result, and await flattens the future echo returns.
  (let ((body (rontolisp:await (rontolisp:read-all (getf env :raw-body)))))
    (if (json-object-p body)
        (let ((message (gethash "message" (rontolisp:json-parse body))))
          (if (stringp message)
              (json-response 200
                             (rontolisp:plist-hash-table (list :message message)))
              (json-response 400
                             (rontolisp:plist-hash-table
                              (list :error "expected a JSON object with a string message field")))))
        (json-response 400
                       (rontolisp:plist-hash-table
                        (list :error "expected a JSON object with a string message field"))))))

(defun not-found (env)
  (text-response 404 (format nil "Not found~%")))

(defun method-not-allowed (env)
  (text-response 405 (format nil "Method Not Allowed~%")))

;; --- routing -----------------------------------------------------------------

;; Dispatch on (path, method), answering 405 when the path exists but the
;; method does not match -- the same behavior as the axum Router. The env
;; plist's :path-info carries the path only, so the comparisons are exact;
;; :request-method is an interned keyword, so the comparisons are eq.
(defun handle (env)
  (let ((path (getf env :path-info))
        (method (getf env :request-method)))
    (cond ((string= path "/")
           (if (eq method :GET) (hello env) (method-not-allowed env)))
          ((string= path "/api/greet")
           (if (eq method :GET) (greet env) (method-not-allowed env)))
          ((string= path "/api/echo")
           (if (eq method :POST) (echo env) (method-not-allowed env)))
          (t (not-found env)))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
