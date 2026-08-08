;;; worker.lisp -- ../httpbin-clack with a real routing library on top.
;;;
;;; The helpers are ../httpbin-clack/worker.lisp's, and so are its clackup call
;;; and synthesized `handle-request` export; what replaces its hand-written
;;; `cond` over :path-info -- and its method check with it -- is tiny-routes.
;;;
;;; The FIRST line decides the module size. "tiny-routes/lite" swaps one
;;; component of the library: the cl-ppcre-backed path-template matcher, and
;;; the :cl-ppcre dependency with it. A template compiles to a scanner at RUN
;;; time, so the full system ships the whole regex engine -- 974,530 B raw
;;; where this build is 408,448 B. The lite matcher takes literal characters
;;; and :name tokens and refuses a regex-shaped template when the route is
;;; built. The README has the numbers and the subset.

(ql:quickload '("clack" "clack-handler-cloudflare-workers" "tiny-routes/lite"))

;; Its own package, like any consumer of a routing library: the route macros
;; and path-parameter come from tiny-routes' exports.
(defpackage :httpbin-tiny-routes (:use :cl :tiny-routes))
(in-package :httpbin-tiny-routes)

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

;; A tiny-routes request IS the Clack environment plist, so these are
;; httpbin-clack's handlers. Gone is its `echo-when`: no handler checks a
;; method any more, because the ROUTE does.

(defun echo (env) (json-response 200 (request-info env)))

(defun echo-with-body (env)
  (let ((info (request-info env)) (body (read-body (getf env :raw-body))))
    (setf (gethash "data" info) body)
    (setf (gethash "json" info) (body-json body))
    (json-response 200 info)))

;; The echo endpoints and the ONE method each answers -- not a second dispatch,
;; but what the catch-all reads to tell a request that DECLINED on its method
;; (405, naming the method that works) from an unknown path (404).
(defparameter *endpoints*
  '(("/get" . :GET) ("/post" . :POST) ("/put" . :PUT) ("/patch" . :PATCH)
    ("/delete" . :DELETE)))

(defun no-route (req)
  (let* ((path (path-info req))
         (allowed (cdr (assoc path *endpoints* :test #'string=))))
    (if allowed
        (json-response 405
                       (rontolisp:plist-hash-table
                        (list :error "method not allowed"
                              :allowed (symbol-name allowed))))
        (json-response 404
         (rontolisp:plist-hash-table (list :error "not found" :path path))))))

;; --- the routes ------------------------------------------------------------

;; One macro per endpoint, for the ONE method it answers: a wrong method
;; DECLINES, no route claims it, and it reaches the single catch-all -- so the
;; 405 needs no route of its own per path.
;;
;; /status/:code is the route the hand-written cond could not spell, and it
;; declines too, on a :code that is not a number. The table above tells the two
;; declines apart: it has no /status entry, so that one gets the 404.
(define-routes *app*
  (define-get "/get" (req) (echo req))
  (define-post "/post" (req) (echo-with-body req))
  (define-put "/put" (req) (echo-with-body req))
  ;; tiny-routes has no define-patch; its method matcher is exported, and that
  ;; is all the other macros add over define-any.
  (wrap-request-matches-method (define-any "/patch" (req) (echo-with-body req))
                               :patch)
  (define-delete "/delete" (req) (echo-with-body req))
  (define-get "/status/:code" (req)
    (let ((code
           (handler-case (parse-integer (path-parameter req :code))
             (error () nil))))
      (when code
        (list code '(:content-type "text/plain; charset=utf-8")
              (list (format nil "~a~%" code))))))
  (define-any "*" (req) (no-route req)))

(clack:clackup *app*
               :server :cloudflare-workers
               :use-thread nil
               :use-default-middlewares nil)
