;;; app.lisp -- a miniature httpbin (https://httpbin.org) for Cloudflare Workers.
;;;
;;; The same five echo endpoints as ../../net/httpbin.lisp, which serves them
;;; over `rontolisp:http-handler`:
;;;
;;;   GET    /get     -> {"args": {...}, "headers": {...}, "method": "GET", "path": "/get"}
;;;   POST   /post    -> the same plus {"data": "<raw body>", "json": <parsed body or null>}
;;;   PUT    /put     -> ditto
;;;   PATCH  /patch   -> ditto
;;;   DELETE /delete  -> ditto
;;;
;;; A wrong method answers 405, an unknown path 404.
;;;
;;; What differs is the transport, and it is the whole point of this directory.
;;; A Worker is not a WASI HTTP host, so there is no server here to run. The
;;; program exports ONE function:
;;;
;;;   handle-request : JSON request string -> JSON response string
;;;
;;; `rontolisp:wasm-export` gives it a host-callable WASM signature, and
;;; src/index.js -- which does have a real `Request` -- calls it. So a request
;;; arrives already parsed into a hash table rather than as a Clack environment,
;;; and the reply is a JSON envelope rather than a Clack response list.
;;;
;;; Nothing here does I/O, which is what lets build.sh compile it with --no-wasi:
;;; the module imports NOTHING, so the Worker instantiates it with an empty
;;; import object and needs no WASI shim. The flip side is that adding a `print`
;;; or a `random` here traps at run time -- see the README.

(rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)

;;; --- the response envelope -------------------------------------------------
;;; What the Worker turns back into a `Response`. `json-stringify` down-cases
;;; keyword keys, so :content-type becomes "content-type".

(defun respond (status content-type body)
  (rontolisp:json-stringify
   (rontolisp:plist-hash-table
    (list :status status
     :headers (rontolisp:plist-hash-table (list :content-type content-type))
     :body body))))

(defun json-response (status object)
  "Reply with OBJECT as the JSON body of a JSON response."
  (respond status "application/json"
           (format nil "~a~%" (rontolisp:json-stringify object))))

(defun error-response (status plist)
  (json-response status (rontolisp:plist-hash-table plist)))

;;; --- the echo document -----------------------------------------------------

;; Parse the body as JSON when it looks like a JSON object or array, and fall
;; back to null when it does not parse. The real httpbin does exactly that;
;; ../../net/httpbin.lisp could not, and says so -- `handler-case` is what
;; closes the gap, and Workers' engine runs it with no flag.
(defun body-json (body)
  (if (and (stringp body) (> (length body) 0)
           (or (eql (char body 0) #\{) (eql (char body 0) #\[)))
      (handler-case (rontolisp:json-parse body) (error () 'null))
      'null))

;; The common echo fields. "args" and "headers" arrive from src/index.js as
;; JSON objects, so they are already string-keyed hash tables and re-serialize
;; as objects -- an empty query is {}, not null.
(defun request-info (request)
  (rontolisp:plist-hash-table
   (list :args (gethash "query" request)
         :headers (gethash "headers" request)
         :method (gethash "method" request)
         :path (gethash "path" request))))

(defun echo (request) (json-response 200 (request-info request)))

(defun echo-with-body (request)
  (let ((info (request-info request)) (body (gethash "body" request)))
    (setf (gethash "data" info) body)
    (setf (gethash "json" info) (body-json body))
    (json-response 200 info)))

;; Echo the request (with the body fields when WITH-BODY is non-nil) only when
;; it used the expected method; otherwise 405.
(defun echo-when (request expected with-body)
  (cond ((not (string= (gethash "method" request) expected))
         (error-response 405
                         (list :error "method not allowed" :allowed expected)))
        (with-body (echo-with-body request))
        (t (echo request))))

;;; --- routing ---------------------------------------------------------------

(defun route (request-json)
  (let* ((request (rontolisp:json-parse request-json))
         (path (gethash "path" request)))
    (cond
     ((string= path "/") (respond 200 "text/plain; charset=utf-8" (index-page)))
     ((string= path "/get") (echo-when request "GET" nil))
     ((string= path "/post") (echo-when request "POST" t))
     ((string= path "/put") (echo-when request "PUT" t))
     ((string= path "/patch") (echo-when request "PATCH" t))
     ((string= path "/delete") (echo-when request "DELETE" t))
     (t (error-response 404 (list :error "not found" :path path))))))

(defun handle-request (request-json)
  ;; A Lisp error would otherwise become a WASM trap, taking the whole instance
  ;; down with it. Answer 500 and keep serving instead.
  (handler-case (route request-json)
    (error (e) (error-response 500 (list :error (format nil "~a" e))))))

;; A function, not a `defparameter`, on purpose: ../httpbin-component builds
;; this same source as a component, and the top-level forms of a component run
;; in its `wasi:cli/run` export, which jco cannot drive. Keeping state inside
;; functions is what lets one source serve both hosts -- see
;; ../httpbin-component/README.md.
(defun index-page ()
  "rontolisp mini httpbin, compiled to WebAssembly and served from a Worker.

  GET    /get      echo the request as JSON
  POST   /post     ...plus the raw body and its parsed JSON
  PUT    /put
  PATCH  /patch
  DELETE /delete

A wrong method answers 405, an unknown path 404, and a body that does not
parse leaves \"json\" null.
")
