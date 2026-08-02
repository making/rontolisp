;; clack.handler.rontolisp: the rontolisp handler backend for Clack, satisfying
;; the built-in ASDF system "clack-handler-rontolisp" (and its dotted alias
;; "clack.handler.rontolisp" -- the system name lack's find-package-or-load
;; derives from the package name). (clack:clackup app :server :rontolisp)
;; resolves here: clack's find-handler probes (find-package
;; "CLACK.HANDLER.RONTOLISP"), loads the system on a miss, then applies the
;; interned RUN. The package is therefore NOT seeded in PackageRegistry -- a
;; pre-seeded package would short-circuit the load and leave run undefined --
;; so this shim carries the defpackage itself (the leaf-module pattern).
;;
;; run bridges the Clack application protocol (env plist in, (status headers
;; body) list out) onto rontolisp's HTTP value model (request plist in,
;; response plist out):
;; - On the interpreter and the JVM backend it starts a STOPPABLE server via
;;   the internal rontolisp::%http-server-* seam and then BLOCKS on the join
;;   (the clack-handler-hunchentoot shape): with clackup's default
;;   :use-thread t the acceptor thread stays alive until clack:stop
;;   destroy-threads it, at which point the interrupted join returns and the
;;   unwind-protect stops that one server.
;; - On the WASM backends (#+rontolisp-wasm) it stores the app and delegates
;;   to the rontolisp:http-handler directive: under --component the host owns
;;   the socket (wasmtime serve; HttpLibrary widens its directive detection
;;   into this defun), so run returns at once, :use-thread must stay nil (the
;;   backends are single-threaded -- clackup's default IS nil there, no
;;   :thread-support feature) and stop is meaningless; on Preview 1 the
;;   directive is a call-time error by design (no incoming TCP).
;;
;; Compatibility notes:
;; - ONE clack server per process: the application is stored in a single
;;   global (the compiled backends dispatch every request through one handler
;;   slot), so a second concurrent clackup replaces the first one's app.
;; - :remote-addr / :remote-port are "" / nil -- rontolisp's request plist
;;   carries neither (extend HttpPlistShape to close the gap).
;; - Duplicate request headers: the LAST value wins in the :headers table.
;; - A response body may be a list of strings, a single string, a
;;   (vector (unsigned-byte 8)) (each octet becomes the character of its code
;;   point) or nil; a PATHNAME body (static files) and a FUNCTION body (the
;;   streaming responder protocol) signal a clear error for now.
;; - :raw-body is pre-drained into a fresh string input stream per request
;;   (correct for lack-request; a lazy pass-through would hand the app
;;   rontolisp's async stream, which Clack apps do not expect).

(defpackage :clack.handler.rontolisp
  (:use :cl)
  (:export :run :stop))

(defvar clack.handler.rontolisp::*app* nil)

(defvar clack.handler.rontolisp::*server-name* "localhost")

(defvar clack.handler.rontolisp::*server-port* 5000)

(defun clack.handler.rontolisp::%headers-table (alist)
  (let ((table (make-hash-table :test 'equal)))
    (dolist (pair alist table)
      (setf (gethash (string-downcase (car pair)) table) (cdr pair)))))

(defun clack.handler.rontolisp::%env (request body)
  (let* ((path (getf request :path))
         (query (getf request :query))
         (headers (clack.handler.rontolisp::%headers-table (getf request :headers)))
         (content-length (gethash "content-length" headers)))
    (list :request-method (intern (string-upcase (getf request :method)) :keyword)
          :script-name ""
          :path-info path
          :query-string query
          :server-name clack.handler.rontolisp::*server-name*
          :server-port clack.handler.rontolisp::*server-port*
          :server-protocol :http/1.1
          :url-scheme "http"
          :request-uri (if query (concatenate 'string path "?" query) path)
          :headers headers
          :content-type (gethash "content-type" headers)
          :content-length (and content-length (parse-integer content-length :junk-allowed t))
          :raw-body (%make-string-input-stream body)
          :remote-addr ""
          :remote-port nil)))

(defun clack.handler.rontolisp::%headers-alist (plist)
  (let ((acc nil))
    (loop for (name value) on plist by #'cddr
          do (push (cons (string-downcase (symbol-name name))
                         (if (stringp value) value (princ-to-string value)))
                   acc))
    (nreverse acc)))

(defun clack.handler.rontolisp::%body-string (body)
  (cond ((null body) "")
        ((stringp body) body)
        ((consp body) (apply #'concatenate 'string body))
        ((typep body '(vector (unsigned-byte 8)))
         (let ((out (make-string-output-stream)))
           (loop for i from 0 below (length body)
                 do (write-char (code-char (aref body i)) out))
           (get-output-stream-string out)))
        ((functionp body)
         (error "clack.handler.rontolisp: a streaming (function) response body is not supported"))
        (t
         (error "clack.handler.rontolisp: a pathname response body is not supported"))))

;; async: the request :body is an asynchronous stream, and await is only legal
;; inside an async body -- the handler seam awaits %bridge's future per request.
(rontolisp:async-defun clack.handler.rontolisp::%bridge (request)
  (let* ((body (rontolisp:await (rontolisp:read-all (getf request :body))))
         (response (funcall clack.handler.rontolisp::*app*
                            (clack.handler.rontolisp::%env request body))))
    (list :status (first response)
          :headers (clack.handler.rontolisp::%headers-alist (second response))
          :body (clack.handler.rontolisp::%body-string (third response)))))

#-rontolisp-wasm
(defun clack.handler.rontolisp:run (app &key (port 5000) (address "127.0.0.1") debug
                                        &allow-other-keys)
  (declare (ignore debug))
  (setf clack.handler.rontolisp::*app* app)
  (setf clack.handler.rontolisp::*server-name* address)
  (setf clack.handler.rontolisp::*server-port* port)
  (let ((server (rontolisp::%http-server-start
                 #'clack.handler.rontolisp::%bridge port address)))
    (unwind-protect
        (progn (rontolisp::%http-server-join server) server)
      (rontolisp::%http-server-stop server))))

#-rontolisp-wasm
(defun clack.handler.rontolisp:stop (server)
  (rontolisp::%http-server-stop server)
  t)

#+rontolisp-wasm
(defun clack.handler.rontolisp:run (app &key (port 5000) (address "127.0.0.1") debug
                                        &allow-other-keys)
  (declare (ignore debug))
  (setf clack.handler.rontolisp::*app* app)
  (setf clack.handler.rontolisp::*server-name* address)
  (setf clack.handler.rontolisp::*server-port* port)
  (rontolisp:http-handler 'clack.handler.rontolisp::%bridge port))

#+rontolisp-wasm
(defun clack.handler.rontolisp:stop (server)
  (declare (ignore server))
  nil)
