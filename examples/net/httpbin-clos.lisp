;; The CLOS flavour of httpbin.lisp: the same miniature httpbin, but the echo
;; responses are CLOS instances instead of hash tables. rontolisp:json-stringify
;; serializes a standard-object as a JSON object -- each slot in definition
;; order -- and a slot may itself hold a hash table (a nested object). So a
;; class gives the fixed-shape envelope while "args" and "headers" stay hash
;; tables (their keys are dynamic). This is exactly how com.inuoe.jzon serializes
;; a standard-object, so switching rontolisp:json-stringify to
;; com.inuoe.jzon:stringify keeps the same output (see httpbin-jzon.lisp).
;;
;;   GET    /         -> an HTML index page (rendered with cl-who) listing the routes below
;;   GET    /get      -> {"args": {...}, "headers": {...}, "method": "GET",  "path": "/get"}
;;   POST   /post     -> the same plus {"data": "<raw body>", "json": <parsed body or null>}
;;   PUT/PATCH/DELETE  -> ditto ; a wrong method 405, an unknown path 404.
;;
;; The index page is the real upstream cl-who ((X)HTML markup library), pulled
;; in with ql:quickload like postgres-web.lisp -- with-html-output-to-string
;; expands the template below at macro-expansion time, so it compiles to
;; ordinary string building on every backend, JVM and WASM included.
;;
;; Run (interpreter, blocking server on :8080):
;;   java -jar $JAR examples/net/httpbin-clos.lisp
;; Run (JVM class; needs the rontolisp jar on the classpath):
;;   java -jar $JAR examples/net/httpbin-clos.lisp -o HttpbinClos.class && java -cp $JAR:. HttpbinClos
;; Run (WASI component under wasmtime serve):
;;   java -jar $JAR examples/net/httpbin-clos.lisp -o httpbin-clos.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y httpbin-clos.wasm
;; Talk to it with:
;;   curl http://127.0.0.1:8080/
;;   curl 'http://127.0.0.1:8080/get?a=1&b=two'
;;   curl -X POST -d '{"name":"rontolisp"}' http://127.0.0.1:8080/post

(ql:quickload "cl-who")

;; --- request helpers ------------------------------------------------------

;; args and headers have dynamic keys, so they stay hash tables (which nest as
;; JSON objects inside the response instance). query-params gives an alist, so
;; rontolisp:alist-hash-table (a subset of alexandria:alist-hash-table) turns
;; it into a hash table; the env :headers already is one.

;; Parse the body as JSON when it looks like a JSON object or array, else the
;; symbol null (which stringifies to JSON null).
(defun body-json (body)
  (if (and (stringp body) (> (length body) 0)
           (or (eql (char body 0) #\{) (eql (char body 0) #\[)))
      (rontolisp:json-parse body)
      'null))

;; --- responses ------------------------------------------------------------

;; The echo envelope as a class: slots serialize as object keys in this order.
;; POST-family responses add the body fields, so they extend the base class --
;; inherited slots come first, giving args/headers/method/path/data/json.
(defclass echo-response ()
  ((args :initarg :args) (headers :initarg :headers) (method :initarg :method)
   (path :initarg :path)))

(defclass echo-with-body-response (echo-response)
  ((data :initarg :data) (json :initarg :json)))

(defun json-response (status obj)
  (list status '(:content-type "application/json")
        (list (format nil "~a~%" (rontolisp:json-stringify obj)))))

;; The index page: a plain cl-who template, no request data to escape.
(defun index-page ()
  (list 200 '(:content-type "text/html; charset=utf-8")
        (list
         (cl-who:with-html-output-to-string (s)
           (:html (:head (:title "rontolisp httpbin"))
                  (:body (:h1 "rontolisp httpbin")
                   (:p "A miniature httpbin: every route below echoes the "
                       "request back as JSON.")
                   (:ul
                    (:li (:code "GET /get") " -- args, headers, method, path")
                    (:li (:code "POST /post")
                     " -- ditto, plus the request body (raw and parsed JSON)")
                    (:li (:code "PUT /put") " -- ditto")
                    (:li (:code "PATCH /patch") " -- ditto")
                    (:li (:code "DELETE /delete") " -- ditto")) (:p "Try it:")
                   (:pre
                    (:code
                     (cl-who:esc "curl 'http://127.0.0.1:8080/get?a=1&b=two'")))
                   (:pre
                    (:code
                     (cl-who:esc
                      "curl -X POST -d '{\"name\":\"rontolisp\"}' http://127.0.0.1:8080/post")))))))))

(defun echo (env)
  (json-response 200
                 (make-instance 'echo-response
                                :args (rontolisp:alist-hash-table
                                       (rontolisp:query-params
                                        (getf env :query-string)))
                                :headers (getf env :headers)
                                :method (symbol-name (getf env :request-method))
                                :path (getf env :path-info))))

(defun echo-with-body (env)
  (json-response 200
                 (make-instance 'echo-with-body-response
                                :args (rontolisp:alist-hash-table
                                       (rontolisp:query-params
                                        (getf env :query-string)))
                                :headers (getf env :headers)
                                :method (symbol-name (getf env :request-method))
                                :path (getf env :path-info)
                                :data (getf env :body)
                                :json (body-json (getf env :body)))))

;; Echo the request (with the body fields when with-body is non-nil) only
;; when the request used the expected method; otherwise 405 (:request-method
;; is an interned keyword, so the comparison is eq). The ad-hoc error
;; objects stay hash tables (rontolisp:plist-hash-table), the flexible tool for
;; a shape that is not worth a class.
(defun echo-when (env expected with-body)
  (cond ((not (eq (getf env :request-method) expected))
         (json-response 405
                        (rontolisp:plist-hash-table
                         (list :error "method not allowed"
                               :allowed (symbol-name expected)))))
        (with-body (echo-with-body env))
        (t (echo env))))

;; --- routing --------------------------------------------------------------

(defun route (env)
  (let ((path (getf env :path-info)))
    (cond ((string= path "/") (index-page))
          ((string= path "/get") (echo-when env :GET nil))
          ((string= path "/post") (echo-when env :POST t))
          ((string= path "/put") (echo-when env :PUT t))
          ((string= path "/patch") (echo-when env :PATCH t))
          ((string= path "/delete") (echo-when env :DELETE t))
          (t (json-response 404
                            (rontolisp:plist-hash-table
                             (list :error "not found" :path path)))))))

;; The env :raw-body is an asynchronous stream on every backend; drain it once
;; here and hand the helpers an env whose :body is the whole string.
(rontolisp:async-defun handle (env)
  (let ((body (rontolisp:await (rontolisp:read-all (getf env :raw-body)))))
    (route (append (list :body body) env))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)
