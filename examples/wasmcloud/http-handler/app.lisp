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
;;     wasmtime serve -W gc=y app.wasm
;; Run (wasmCloud; .wash/config.yaml builds app.wasm and enables the gc proposal):
;;   wash dev    # in this directory; serves on :8000
;; Talk to it with:
;;   curl http://127.0.0.1:8080/
;;   curl 'http://127.0.0.1:8080/api/greet?name=rontolisp'
;;   curl -X POST -d '{"message":"hi"}' http://127.0.0.1:8080/api/echo

;; --- request helpers --------------------------------------------------------

;; The path part of "path?query" (up to the first ?).
(defun path-only (path)
  (let ((q (position #\? path)))
    (if q (subseq path 0 q) path)))

;; The query part of "path?query", or "" when there is none.
(defun query-of (path)
  (let ((q (position #\? path)))
    (if q (subseq path (+ q 1)) "")))

;; The value of `name` in "a=1&b=two", or nil (no %XX decoding).
(defun query-param (query name)
  (unless (string= query "")
    (let* ((amp (position #\& query))
           (pair (if amp (subseq query 0 amp) query))
           (eq-pos (position #\= pair)))
      (if (and eq-pos (string= (subseq pair 0 eq-pos) name))
          (subseq pair (+ eq-pos 1))
          (when amp (query-param (subseq query (+ amp 1)) name))))))

;; t when the body looks like a JSON object (json-parse signals on garbage,
;; and rontolisp has no condition handling to recover from that).
(defun json-object-p (body)
  (and (stringp body) (> (length body) 0) (eql (char body 0) #\{)))

;; --- responses ---------------------------------------------------------------

(defun text-response (status body)
  (list :status status
        :headers (list (cons "content-type" "text/plain"))
        :body body))

(defun json-response (status obj)
  (list :status status
        :headers (list (cons "content-type" "application/json"))
        :body (format nil "~a~%" (rontolisp:json-stringify obj))))

;; --- handlers ----------------------------------------------------------------

(defun hello (request)
  (text-response 200 (format nil "Hello from wasmCloud!~%")))

(defun greet (request)
  (let ((name (query-param (query-of (getf request :path)) "name")))
    (text-response 200 (format nil "Hello, ~a!~%" (if name name "world")))))

(defun echo (request)
  (let ((body (getf request :body)))
    (if (json-object-p body)
        (let ((message (getf (rontolisp:json-parse body) :message)))
          (if (stringp message)
              (json-response 200 (list :message message))
              (json-response 400 (list :error "expected a JSON object with a string message field"))))
        (json-response 400 (list :error "expected a JSON object with a string message field")))))

(defun not-found (request)
  (text-response 404 (format nil "Not found~%")))

(defun method-not-allowed (request)
  (text-response 405 (format nil "Method Not Allowed~%")))

;; --- routing -----------------------------------------------------------------

;; Dispatch on (path, method), answering 405 when the path exists but the
;; method does not match -- the same behavior as the axum Router.
(defun handle (request)
  (let ((path (path-only (getf request :path)))
        (method (getf request :method)))
    (cond ((string= path "/")
           (if (string= method "GET") (hello request) (method-not-allowed request)))
          ((string= path "/api/greet")
           (if (string= method "GET") (greet request) (method-not-allowed request)))
          ((string= path "/api/echo")
           (if (string= method "POST") (echo request) (method-not-allowed request)))
          (t (not-found request)))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
