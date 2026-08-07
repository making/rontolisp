;;; worker.lisp -- the WHOLE Cloudflare Worker: one file, one clackup call.
;;;
;;; Everything from the (ql:quickload "clack") below down to `app` is
;;; ../../net/httpbin-clack.lisp VERBATIM -- the same five echo endpoints, the
;;; same helpers, no Cloudflare anywhere in them. That file's last form is
;;;
;;;   (clack:clackup #'app :server :rontolisp :port 8080 :use-thread nil)
;;;
;;; and the Cloudflare port is that one form's ARGUMENTS. Nothing else. The
;;; application does not know it is on a Worker, and it does not have to: `app`
;;; takes the Clack environment plist and returns the Clack (status headers
;;; body) list, so the same function runs on hunchentoot, on woo, under
;;; `wasmtime serve` and on the JVM, unchanged.
;;;
;;; A Worker hands over a request JavaScript has already parsed rather than a
;;; socket, so :cloudflare-workers is a handler backend with nothing to bind.
;;; Its `run` stores the application and returns; what the host calls instead of
;;; connecting is one exported function,
;;;
;;;   handle-request : JSON request string -> JSON response string
;;;
;;; which src/index.js -- which does have a real `Request` -- calls. Nothing
;;; here declares that export: rontolisp:wasm-export needs a literal name at
;;; compile time, so the compiler synthesizes the export (and the bridge to the
;;; handler backend's `dispatch`) from a marker the backend's `run` carries.
;;; See doc/en/guides/clack.md.
;;;
;;; The adapter is not written here either. clack-handler-cloudflare-workers is a
;;; built-in handler backend, the sibling of the clack-handler-rontolisp one
;;; that clackup uses when it DOES own a socket, and its `handle` is the whole
;;; bridge: it builds the Clack environment from the JSON envelope, runs the
;;; application, normalizes the Clack response back, and answers 500 rather than
;;; trapping if the application signals. It converts nothing itself -- rontolisp's
;;; server protocol IS Clack's, so it rides the same backend-free
;;; rontolisp::%http-make-env / %http-normalize-response entry points every other
;;; transport meets in. The envelope both sides speak is documented there.
;;;
;;; The two keywords are not incantation, they are what this host is:
;;;
;;; - :use-thread nil is for the OTHER backends. On WASM it is already the
;;;   default (single-threaded by construction), but the interpreter and the JVM
;;;   have threads, so clackup would otherwise store the application on one --
;;;   and demo.lisp, which drives this file without Cloudflare, would race it.
;;; - :use-default-middlewares nil drops lack's `backtrace` middleware, whose
;;;   whole job is to print a report to *error-output* -- which a reactor does
;;;   not have. It also prints on an error the application CATCHES, and on the
;;;   compiled backends `(symbol-value '*error-output*)` is itself unbound
;;;   today, so leaving it in replaces a handled error with a failure. The
;;;   handler backend already answers 500 for anything the application signals.
;;;
;;; Nothing here does I/O, which is what lets build.sh compile it with --no-wasi:
;;; the module imports NOTHING, so the Worker instantiates it with an empty
;;; import object and needs no WASI shim. clackup's own start-up banner is the
;;; one exception, and it is not a trap: under --no-wasi stdout is a SINK, so
;;; the bytes are simply discarded (a `print` you add here goes the same way --
;;; see the README).

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

(ql:quickload "clack-handler-cloudflare-workers")

(clack:clackup #'app
               :server :cloudflare-workers
               :use-thread nil
               :use-default-middlewares nil)
