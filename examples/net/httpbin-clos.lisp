;; The CLOS flavour of httpbin.lisp: the same miniature httpbin, but the echo
;; responses are CLOS instances instead of hash tables. rontolisp:json-stringify
;; serializes a standard-object as a JSON object -- each slot in definition
;; order -- and a slot may itself hold a hash table (a nested object). So a
;; class gives the fixed-shape envelope while "args" and "headers" stay hash
;; tables (their keys are dynamic). This is exactly how com.inuoe.jzon serializes
;; a standard-object, so switching rontolisp:json-stringify to
;; com.inuoe.jzon:stringify keeps the same output (see httpbin-jzon.lisp).
;;
;;   GET    /get      -> {"args": {...}, "headers": {...}, "method": "GET",  "path": "/get"}
;;   POST   /post     -> the same plus {"data": "<raw body>", "json": <parsed body or null>}
;;   PUT/PATCH/DELETE  -> ditto ; a wrong method 405, an unknown path 404.
;;
;; Run (interpreter, blocking server on :8080):
;;   java -jar $JAR examples/net/httpbin-clos.lisp
;; Run (JVM class; needs the rontolisp jar on the classpath):
;;   java -jar $JAR examples/net/httpbin-clos.lisp -o HttpbinClos.class && java -cp $JAR:. HttpbinClos
;; Run (WASI component under wasmtime serve):
;;   java -jar $JAR examples/net/httpbin-clos.lisp -o httpbin-clos.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y httpbin-clos.wasm
;; Talk to it with:
;;   curl 'http://127.0.0.1:8080/get?a=1&b=two'
;;   curl -X POST -d '{"name":"rontolisp"}' http://127.0.0.1:8080/post

;; --- request helpers ------------------------------------------------------

;; args and headers have dynamic keys, so they stay hash tables (which nest as
;; JSON objects inside the response instance). query-params and the request
;; headers are already alists, so rontolisp:alist-hash-table (a subset of
;; alexandria:alist-hash-table) turns each into a hash table directly.

;; Parse the body as JSON when it looks like a JSON object or array, else the
;; symbol null (which stringifies to JSON null).
(defun body-json (body)
  (if (and (stringp body)
           (> (length body) 0)
           (or (eql (char body 0) #\{) (eql (char body 0) #\[)))
      (rontolisp:json-parse body)
      'null))

;; --- responses ------------------------------------------------------------

;; The echo envelope as a class: slots serialize as object keys in this order.
;; POST-family responses add the body fields, so they extend the base class --
;; inherited slots come first, giving args/headers/method/path/data/json.
(defclass echo-response ()
  ((args :initarg :args)
   (headers :initarg :headers)
   (method :initarg :method)
   (path :initarg :path)))

(defclass echo-with-body-response (echo-response)
  ((data :initarg :data)
   (json :initarg :json)))

(defun json-response (status obj)
  (list :status status
        :headers (list (cons "content-type" "application/json"))
        :body (format nil "~a~%" (rontolisp:json-stringify obj))))

(defun echo (request)
  (json-response 200
                 (make-instance 'echo-response
                                :args (rontolisp:alist-hash-table (rontolisp:query-params (getf request :query)))
                                :headers (rontolisp:alist-hash-table (getf request :headers))
                                :method (getf request :method)
                                :path (getf request :path))))

(defun echo-with-body (request)
  (json-response 200
                 (make-instance 'echo-with-body-response
                                :args (rontolisp:alist-hash-table (rontolisp:query-params (getf request :query)))
                                :headers (rontolisp:alist-hash-table (getf request :headers))
                                :method (getf request :method)
                                :path (getf request :path)
                                :data (getf request :body)
                                :json (body-json (getf request :body)))))

;; Echo the request (with the body fields when with-body is non-nil) only
;; when the request used the expected method; otherwise 405. The ad-hoc error
;; objects stay hash tables (rontolisp:plist-hash-table), the flexible tool for
;; a shape that is not worth a class.
(defun echo-when (request expected with-body)
  (cond ((not (string= (getf request :method) expected))
         (json-response 405 (rontolisp:plist-hash-table
                             (list :error "method not allowed" :allowed expected))))
        (with-body (echo-with-body request))
        (t (echo request))))

;; --- routing --------------------------------------------------------------

(defun route (request)
  (let ((path (getf request :path)))
    (cond ((string= path "/get") (echo-when request "GET" nil))
          ((string= path "/post") (echo-when request "POST" t))
          ((string= path "/put") (echo-when request "PUT" t))
          ((string= path "/patch") (echo-when request "PATCH" t))
          ((string= path "/delete") (echo-when request "DELETE" t))
          (t (json-response 404 (rontolisp:plist-hash-table
                                 (list :error "not found" :path path)))))))

;; The request :body is an asynchronous stream on every backend; drain it once
;; here and hand the helpers a request whose :body is the whole string.
(rontolisp:async-defun handle (request)
  (let ((body (rontolisp:await (rontolisp:read-all (getf request :body)))))
    (route (append (list :body body) request))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
