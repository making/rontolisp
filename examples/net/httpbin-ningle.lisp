;; The ningle flavour of httpbin-clack.lisp: the same five echo endpoints, with
;; the hand-written `cond` over :path-info -- and its method check with it --
;; replaced by ningle. Where net/httpbin-tiny-routes.lisp composes a list of
;; route handlers, ningle hangs routes on an application OBJECT: the app is
;; `(make-instance 'ningle:app)` and every route is a `setf`, one per method,
;; so a wrong method matches no rule at all.
;;
;; The two ningle-specific moves are worth naming:
;;
;;   * a controller receives the PARAMETER alist, not the Clack environment.
;;     The environment is still one accessor away -- lack.request:request-env
;;     on ningle:*request*, the request object bound for the current request --
;;     so httpbin-clack's helpers below are its own, unchanged.
;;   * the 404 is a METHOD, not a catch-all route. Overriding ningle:not-found
;;     is where "no rule matched" is answered, and it is what tells httpbin's
;;     405 (a known path, wrong method) from its 404.
;;
;; Everything below the quickload is a portable Clack application, exactly as
;; in httpbin-clack.lisp: :server :rontolisp means "serve on this target's
;; native inbound transport", so this one file is also a Cloudflare Worker
;; under --no-wasi -- the shape examples/cloudflare-workers/hello-ningle
;; deploys with two routes instead of six.
;;
;; Run (the first run downloads clack/lack/ningle into ~/.rontolisp/quicklisp):
;;   rontolisp examples/net/httpbin-ningle.lisp
;;   rontolisp examples/net/httpbin-ningle.lisp -o HttpbinNingle.class && \
;;     java -cp rontolisp-exec.jar:. HttpbinNingle
;;   rontolisp examples/net/httpbin-ningle.lisp -o httpbin-ningle.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y \
;;       httpbin-ningle.wasm
;; Preview 1 has no incoming TCP: the program compiles, clackup fails at run
;; time. Under --component the host owns the socket, so :port is ignored.
;;
;;   curl 'http://127.0.0.1:8080/get?a=1&b=two'
;;   curl -X POST -d '{"name":"rontolisp"}' http://127.0.0.1:8080/post
;;   curl http://127.0.0.1:8080/status/418

(ql:quickload '("clack" "ningle"))

(defpackage :httpbin-ningle (:use :cl))
(in-package :httpbin-ningle)

;; --- request helpers (httpbin-clack's, verbatim) ---------------------------

(defun read-body (stream)
  (if (null stream)
      ""
      (with-output-to-string (out)
        (do ((ch (read-char stream nil nil) (read-char stream nil nil)))
            ((null ch))
          (write-char ch out)))))

(defun body-json (body)
  (if (and (stringp body) (> (length body) 0)
           (or (eql (char body 0) #\{) (eql (char body 0) #\[)))
      (handler-case (rontolisp:json-parse body) (error () 'null))
      'null))

;; --- responses (httpbin-clack's, verbatim) ---------------------------------

(defun json-response (status obj)
  (list status '(:content-type "application/json")
        (list (format nil "~a~%" (rontolisp:json-stringify obj)))))

(defun request-info (env)
  (rontolisp:plist-hash-table
   (list :args (rontolisp:alist-hash-table
                (rontolisp:query-params (getf env :query-string)))
         :headers (getf env :headers)
         :method (symbol-name (getf env :request-method))
         :path (getf env :path-info))))

;; --- the handlers ----------------------------------------------------------

;; The Clack environment for the request being served. ningle hands a
;; controller the parameter alist and keeps the request object in a special, so
;; this is where the two conventions meet -- and below it httpbin-clack's
;; handlers are unchanged. Gone is its `echo-when`: no handler checks a method
;; any more, because the ROUTE does.
(defun request-env () (lack.request:request-env ningle:*request*))

(defun echo (env) (json-response 200 (request-info env)))

(defun echo-with-body (env)
  (let ((info (request-info env)) (body (read-body (getf env :raw-body))))
    (setf (gethash "data" info) body)
    (setf (gethash "json" info) (body-json body))
    (json-response 200 info)))

;; The echo endpoints and the ONE method each answers -- not a second dispatch,
;; but what not-found reads to tell a request that matched no rule ON A KNOWN
;; PATH (405, naming the method that works) from an unknown path (404).
(defparameter *endpoints*
  '(("/get" . :GET) ("/post" . :POST) ("/put" . :PUT) ("/patch" . :PATCH)
    ("/delete" . :DELETE)))

;; --- the routes ------------------------------------------------------------

(defvar *app* (make-instance 'ningle:app))

;; One route per endpoint, for the ONE method it answers: :method defaults to
;; :GET, so only the four others name it. A request with the wrong method
;; matches no rule and reaches not-found below -- so the 405 needs no route of
;; its own per path.
(setf (ningle:route *app* "/get")
      (lambda (params)
        (declare (ignore params))
        (echo (request-env))))

(setf (ningle:route *app* "/post" :method :POST)
      (lambda (params)
        (declare (ignore params))
        (echo-with-body (request-env))))

(setf (ningle:route *app* "/put" :method :PUT)
      (lambda (params)
        (declare (ignore params))
        (echo-with-body (request-env))))

(setf (ningle:route *app* "/patch" :method :PATCH)
      (lambda (params)
        (declare (ignore params))
        (echo-with-body (request-env))))

(setf (ningle:route *app* "/delete" :method :DELETE)
      (lambda (params)
        (declare (ignore params))
        (echo-with-body (request-env))))

;; /status/:code is the route the hand-written cond could not spell. Returning
;; nil is NOT a decline in ningle -- it answers an empty body -- so a :code that
;; is not a number hands the request to not-found itself, which is the same
;; method an unmatched path reaches. The table above has no /status entry, so
;; that one gets the 404.
(setf (ningle:route *app* "/status/:code")
      (lambda (params)
        (let ((code
               (handler-case (parse-integer (cdr (assoc :code params)))
                 (error () nil))))
          (if code
              (list code '(:content-type "text/plain; charset=utf-8")
                    (list (format nil "~a~%" code)))
              (ningle:not-found *app*)))))

;; The 404 is ningle's own extension point: a method on the application class,
;; called when no rule matched. It answers the Clack triple directly, which
;; ningle passes through untouched.
(defmethod ningle:not-found ((app ningle:app))
  (let* ((path (lack.request:request-path-info ningle:*request*))
         (allowed (cdr (assoc path *endpoints* :test #'string=))))
    (if allowed
        (json-response 405
                       (rontolisp:plist-hash-table
                        (list :error "method not allowed"
                              :allowed (symbol-name allowed))))
        (json-response 404
         (rontolisp:plist-hash-table (list :error "not found" :path path))))))

(clack:clackup *app* :server :rontolisp :port 8080 :use-thread nil)
