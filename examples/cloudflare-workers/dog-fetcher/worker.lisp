;;; A Worker that itself makes an outgoing HTTP request -- the proxy shape of
;;; ../../net/dog-fetcher.lisp, routed with tiny-routes/lite. The client is
;;; rontolisp:fetch, THE SAME (await (fetch ...)) that runs on the interpreter,
;;; the JVM and a wasi:http component: --host-fetch lowers it onto the Worker
;;; runtime's own fetch, imported as env.fetch and suspended through JSPI
;;; (src/index.js), so the source no longer spells its own transport.
;;;
;;; :server :rontolisp picks the transport per target at read time, so THIS
;;; ONE SOURCE runs on every backend (the README has the commands):
;;;   interpreter / JVM  -- a real socket on :8080, fetch over the JDK client
;;;   --component        -- wasmtime serve, fetch over wasi:http
;;;   --no-wasi --host-fetch (build.sh) -- the Worker: the host calls the
;;;                         synthesized handle-request export, fetch is env.fetch

(ql:quickload '("clack" "tiny-routes/lite"))

(defun json-response (status obj)
  (list status '(:content-type "application/json")
        (list (format nil "~a~%" (rontolisp:json-stringify obj)))))

(defun error-response (status message)
  (json-response status (rontolisp:plist-hash-table (list :error message))))

;; One upstream round trip: dog.ceo answers {"message": <url or reason>,
;; "status": "success"|"error"}. Awaiting needs an async-defun, and a future
;; settles to ONE value, so the (url status) pair rides a list -- status 0
;; means nothing came back at all (the transport error fetch signals at await),
;; which is the only case the routes answer 502 for.
(rontolisp:async-defun dog-image (path)
  (handler-case (let* ((res
                        (rontolisp:await
                         (rontolisp:fetch
                          (concatenate 'string "https://dog.ceo/api" path))))
                       (status (getf res :status))
                       (body
                        (rontolisp:await (rontolisp:read-all (getf res :body))))
                       (answer (rontolisp:json-parse body)))
                  (list (and (eql status 200)
                             (equal (gethash "status" answer) "success")
                             (gethash "message" answer)) status))
    (error () (list nil 0))))

;; The route bodies below are synchronous (tiny-routes composes plain
;; functions), so they cannot await -- they return the async-defun's FUTURE as
;; the response, and the reactor transport resolves it at the boundary.
(rontolisp:async-defun random-dog-response ()
  (let ((dog (car (rontolisp:await (dog-image "/breeds/image/random")))))
    (if dog
        (json-response 200 (rontolisp:plist-hash-table (list :dog dog)))
        (error-response 502 "the dog API did not answer"))))

(rontolisp:async-defun breed-response (breed)
  (let* ((result
          (rontolisp:await
           (dog-image (format nil "/breed/~a/images/random" breed))))
         (dog (car result))
         (status (car (cdr result))))
    (cond (dog (json-response 200
                (rontolisp:plist-hash-table (list :breed breed :dog dog))))
          ((eql status 0) (error-response 502 "the dog API did not answer"))
          (t (error-response 404 "no such breed")))))

;;; --- the routes --------------------------------------------------------------

;; A breed reaches the upstream inside a URL, so it is checked first. nil
;; DECLINES the route, which drops the request into the catch-all 404.
(defun valid-breed (breed)
  (and (plusp (length breed))
       (every (lambda (c) (or (alpha-char-p c) (eql c #\-))) breed) breed))

(tiny:define-routes *routes*
  (tiny:define-get "/" () (random-dog-response))
  (tiny:define-get "/breed/:breed" (req)
    (let ((breed
           (valid-breed (string-downcase (tiny:path-parameter req :breed)))))
      (when breed (breed-response breed))))
  (tiny:define-any "*" (req)
    (error-response 404 (format nil "no route for ~a" (tiny:path-info req)))))

(clack:clackup *routes* :server :rontolisp :port 8080 :use-thread nil)
