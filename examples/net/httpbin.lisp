;; A miniature httpbin (https://httpbin.org) built on rontolisp:http-handler --
;; the advanced companion of http-handler.lisp. Five echo endpoints respond
;; with a JSON document describing the request, built with
;; rontolisp:json-stringify (and rontolisp:json-parse for the request body):
;;
;;   GET    /get      -> {"args": {...}, "headers": {...}, "method": "GET",  "path": "/get"}
;;   POST   /post     -> the same plus {"data": "<raw body>", "json": <parsed body or null>}
;;   PUT    /put      -> ditto
;;   PATCH  /patch    -> ditto
;;   DELETE /delete   -> ditto
;;
;; A wrong method answers 405, an unknown path 404. Query strings are parsed
;; into "args" with rontolisp:query-params (keys and values url-decoded);
;; "json" is filled only when the body starts
;; with '{' or '[' (malformed JSON then signals an error -- rontolisp has no
;; condition handling to fall back to null like the real httpbin).
;; On the JVM and WASM component backends "headers" is always {} (request
;; headers are not marshalled there yet) and the response content-type header
;; is ignored; only the interpreter passes headers through.
;;
;; Run (interpreter, blocking server on :8080):
;;   java -jar $JAR examples/net/httpbin.lisp
;; Run (JVM class; needs the rontolisp jar on the classpath):
;;   java -jar $JAR examples/net/httpbin.lisp -o Httpbin.class && java -cp $JAR:. Httpbin
;; Run (WASI component under wasmtime serve):
;;   java -jar $JAR examples/net/httpbin.lisp -o httpbin.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y httpbin.wasm
;; Talk to it with:
;;   curl 'http://127.0.0.1:8080/get?a=1&b=two'
;;   curl -X POST -d '{"name":"rontolisp"}' http://127.0.0.1:8080/post

;; --- request helpers ------------------------------------------------------

;; The query parameters (rontolisp:query-params' url-decoded alist) rebuilt
;; as a hash table, so arbitrary key strings serialize as a JSON object and
;; an empty (or missing) query still serializes as {}.
(defun args-table (query)
  (let ((args (make-hash-table)))
    (dolist (pair (rontolisp:query-params query))
      (setf (gethash (car pair) args) (cdr pair)))
    args))

;; Request headers arrive as an alist of (name . value); rebuild it as a hash
;; table so it serializes as a JSON object.
(defun headers-table (headers)
  (let ((table (make-hash-table)))
    (dolist (header headers)
      (setf (gethash (car header) table) (cdr header)))
    table))

;; Parse the body as JSON when it looks like a JSON object or array.
(defun body-json (body)
  (if (and (stringp body)
           (> (length body) 0)
           (or (eql (char body 0) #\{) (eql (char body 0) #\[)))
      (rontolisp:json-parse body :hash-table)
      nil))

;; --- responses ------------------------------------------------------------

(defun json-response (status obj)
  (list :status status
        :headers (list (cons "content-type" "application/json"))
        :body (format nil "~a~%" (rontolisp:json-stringify obj))))

;; The common echo fields, as a plist (rontolisp:json-stringify serializes a
;; keyword plist as a JSON object, preserving this key order).
(defun request-info (request)
  (list :args (args-table (getf request :query))
        :headers (headers-table (getf request :headers))
        :method (getf request :method)
        :path (getf request :path)))

(defun echo (request)
  (json-response 200 (request-info request)))

(defun echo-with-body (request)
  (json-response 200
                 (append (request-info request)
                         (list :data (getf request :body)
                               :json (body-json (getf request :body))))))

;; Echo the request (with the body fields when with-body is non-nil) only
;; when the request used the expected method; otherwise 405.
(defun echo-when (request expected with-body)
  (cond ((not (string= (getf request :method) expected))
         (json-response 405 (list :error "method not allowed" :allowed expected)))
        (with-body (echo-with-body request))
        (t (echo request))))

;; --- routing --------------------------------------------------------------

;; The request plist's :path carries the path only (the query string arrives
;; separately as :query), so the comparisons are exact.
(defun route (request)
  (let ((path (getf request :path)))
    (cond ((string= path "/get") (echo-when request "GET" nil))
          ((string= path "/post") (echo-when request "POST" t))
          ((string= path "/put") (echo-when request "PUT" t))
          ((string= path "/patch") (echo-when request "PATCH" t))
          ((string= path "/delete") (echo-when request "DELETE" t))
          (t (json-response 404 (list :error "not found" :path path))))))

;; The request :body is an asynchronous stream on every backend; drain it once
;; here and hand the helpers a request whose :body is the whole string (getf
;; finds the prepended pair first).
(rontolisp:async-defun handle (request)
  (let ((body (rontolisp:await (rontolisp:read-all (getf request :body)))))
    (route (append (list :body body) request))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
