;;; app.lisp -- the Clack application, and NOTHING else.
;;;
;;; This file is examples/net/httpbin-clack.lisp with its last form -- the
;;; (clack:clackup #'app ...) call -- removed. Nothing has been added: no
;;; Cloudflare, no wasm-export, no JSON envelope, not even a mention that a
;;; Worker exists. That is the claim this directory makes, and splitting it into
;;; its own file is what makes the claim checkable rather than a comment: `app`
;;; takes the Clack environment plist and returns the Clack (status headers body)
;;; list, so the same function runs on hunchentoot, on woo, under
;;; `wasmtime serve` and on the JVM, unchanged.
;;;
;;; worker.lisp is the other half: it loads this file and supplies the transport
;;; a Worker needs. clackup is what it replaces.
;;;
;;; The five echo endpoints (see ../httpbin for the JSON they answer):
;;;
;;;   GET    /get     -> {"args": {...}, "headers": {...}, "method": "GET", "path": "/get"}
;;;   POST   /post    -> the same plus {"data": "<raw body>", "json": <parsed body or null>}
;;;   PUT    /put     -> ditto
;;;   PATCH  /patch   -> ditto
;;;   DELETE /delete  -> ditto

(ql:quickload "clack")

;; --- request helpers ------------------------------------------------------

(defun read-body (stream)
  (if (null stream)
      ""
      (with-output-to-string (out)
        (do ((ch (read-char stream nil nil) (read-char stream nil nil)))
            ((null ch))
          (write-char ch out)))))

;; Parse the body as JSON when it looks like a JSON object or array, and fall
;; back to null when it does not parse -- which is what the real httpbin does.
(defun body-json (body)
  (if (and (stringp body) (> (length body) 0)
           (or (eql (char body 0) #\{) (eql (char body 0) #\[)))
      (handler-case (rontolisp:json-parse body) (error () 'null))
      'null))

;; --- responses ------------------------------------------------------------

(defun json-response (status obj)
  (list status '(:content-type "application/json")
        (list (format nil "~a~%" (rontolisp:json-stringify obj)))))

;; The common echo fields, as a JSON object: plist-hash-table and
;; alist-hash-table give json-stringify the string-keyed hash tables it
;; serializes as objects (:method becomes "method"; an empty query still
;; renders {}), and the env :headers already is one.
(defun request-info (env)
  (rontolisp:plist-hash-table
   (list :args (rontolisp:alist-hash-table
                (rontolisp:query-params (getf env :query-string)))
         :headers (getf env :headers)
         :method (symbol-name (getf env :request-method))
         :path (getf env :path-info))))

(defun echo (env) (json-response 200 (request-info env)))

(defun echo-with-body (env)
  (let ((info (request-info env)) (body (read-body (getf env :raw-body))))
    (setf (gethash "data" info) body)
    (setf (gethash "json" info) (body-json body))
    (json-response 200 info)))

;; Echo the request only when it used the expected method; otherwise 405.
;; :request-method is an interned keyword, so the comparison is eq.
(defun echo-when (env expected with-body)
  (cond ((not (eq (getf env :request-method) expected))
         (json-response 405
                        (rontolisp:plist-hash-table
                         (list :error "method not allowed"
                               :allowed (symbol-name expected)))))
        (with-body (echo-with-body env))
        (t (echo env))))

;; --- the Clack application ------------------------------------------------

;; :path-info carries the (percent-decoded) path only -- the query string
;; arrives separately -- so the comparisons are exact.
(defun app (env)
  (let ((path (getf env :path-info)))
    (cond ((string= path "/get") (echo-when env :GET nil))
          ((string= path "/post") (echo-when env :POST t))
          ((string= path "/put") (echo-when env :PUT t))
          ((string= path "/patch") (echo-when env :PATCH t))
          ((string= path "/delete") (echo-when env :DELETE t))
          (t
           (json-response 404
                          (rontolisp:plist-hash-table
                           (list :error "not found" :path path)))))))
