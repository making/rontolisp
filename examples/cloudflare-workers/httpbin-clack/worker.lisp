;;; worker.lisp -- a mini httpbin as a Clack application, installed on the
;;; Worker by clack:clackup.
;;;
;;; Everything from `read-body` down to `*app*` is ../httpbin/worker.lisp's
;;; application VERBATIM -- and ../../net/httpbin-clack.lisp's -- so the three
;;; differ in one thing only: what puts the application on the host. There it is
;;; thirty hand-written lines under the application and clack never loads; here
;;; it is the last form of this file.
;;;
;;; :server :reactor is the built-in handler backend for a host that CALLS you
;;; instead of handing you a socket. Its `run` binds nothing -- it stores the
;;; application -- and the compiler synthesizes the export src/index.js calls
;;; (handle-request: a JSON request string in, a JSON response string out).
;;; Nothing here declares that export, because rontolisp:wasm-export needs a
;;; literal name at compile time and a clackup call has none to give.
;;;
;;; :reactor means host-driven on EVERY backend, which is what lets check.lisp
;;; drive this Worker on the interpreter and the JVM as well. The other
;;; designator is ../httpbin-clack-one-source: `:server :rontolisp` serves on
;;; whatever the compile target's native transport is, so the file it deploys
;;; is unchanged from the one that binds a socket locally.
;;;
;;; :use-thread nil is what this host is, not boilerplate: it is already the
;;; default on WASM, and on the interpreter and the JVM it stops clackup from
;;; storing the application on a thread the next form would race. clackup's
;;; default middlewares stay on -- lack's backtrace middleware writes its report
;;; to *error-output*, a discarding sink under --no-wasi and real standard error
;;; everywhere else.

(ql:quickload '("clack" "clack-handler-reactor"))

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

;; A Clack application is a function VALUE -- what clackup, lack:builder and
;; every middleware take and return -- so it is a lambda in a variable rather
;; than a defun. :path-info carries the (percent-decoded) path only; the query
;; string arrives separately, so the comparisons are exact.
(defparameter *app*
  (lambda (env)
    (let ((path (getf env :path-info)))
      (cond ((string= path "/get") (echo-when env :GET nil))
            ((string= path "/post") (echo-when env :POST t))
            ((string= path "/put") (echo-when env :PUT t))
            ((string= path "/patch") (echo-when env :PATCH t))
            ((string= path "/delete") (echo-when env :DELETE t))
            (t (json-response 404
                              (rontolisp:plist-hash-table
                               (list :error "not found" :path path))))))))

(clack:clackup *app* :server :reactor :use-thread nil)
