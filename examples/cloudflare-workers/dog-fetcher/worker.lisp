;;; A Worker that itself makes an outgoing HTTP request -- the proxy shape of
;;; ../../net/dog-fetcher.lisp, routed with tiny-routes/lite. rontolisp:fetch is
;;; wasi:http and a --no-wasi reactor imports no WASI, so the client is the
;;; host's own `fetch`, imported.

(ql:quickload '("clack" "clack-handler-reactor" "tiny-routes/lite"))

;;; --- the way out -------------------------------------------------------------

;; URL in, JSON envelope out:
;;   {"status": 200, "body": "..."}   the upstream answered
;;   {"status": 0, "error": "..."}    the request never completed
;; JSPI (src/index.js) suspends the wasm stack for the promise, so this is a
;; plain call and the handler below is not an async-defun.
(rontolisp:wasm-import 'host-fetch
                       :from "env"
                       :as "fetch"
                       :params '(:string)
                       :returns :string)

;;; --- the upstream ------------------------------------------------------------

(defun json (object) (format nil "~a~%" (rontolisp:json-stringify object)))

(defun error-response (status message)
  (tiny:make-response :status status
   :body (json (rontolisp:plist-hash-table (list :error message)))))

;; dog.ceo answers {"message": <url or reason>, "status": "success"|"error"}:
;; the envelope carries the transport outcome, the parsed body the API's.
;; Returns the picture URL or nil, and the host's status -- 0 when nothing
;; came back at all, which is the only case the routes answer 502 for.
(defun dog-image (path)
  (handler-case (let* ((envelope
                        (rontolisp:json-parse
                         (host-fetch
                          (concatenate 'string "https://dog.ceo/api" path))))
                       (status (gethash "status" envelope))
                       (answer
                        (rontolisp:json-parse (gethash "body" envelope ""))))
                  (values (and (eql status 200)
                               (equal (gethash "status" answer) "success")
                               (gethash "message" answer)) status))
    (error () (values nil 0))))

;;; --- the routes --------------------------------------------------------------

;; A breed reaches the upstream inside a URL, so it is checked first. nil
;; DECLINES the route, which drops the request into the catch-all 404.
(defun valid-breed (breed)
  (and (plusp (length breed))
       (every (lambda (c) (or (alpha-char-p c) (eql c #\-))) breed) breed))

(tiny:define-routes *routes*
  (tiny:define-get "/" ()
    (let ((dog (dog-image "/breeds/image/random")))
      (if dog
          (tiny:ok (json (rontolisp:plist-hash-table (list :dog dog))))
          (error-response 502 "the dog API did not answer"))))
  (tiny:define-get "/breed/:breed" (req)
    (let ((breed
           (valid-breed (string-downcase (tiny:path-parameter req :breed)))))
      (when breed
        (multiple-value-bind (dog status)
            (dog-image (format nil "/breed/~a/images/random" breed))
          (cond
           (dog
            (tiny:ok
             (json (rontolisp:plist-hash-table (list :breed breed :dog dog)))))
           ((eql status 0) (error-response 502 "the dog API did not answer"))
           (t (error-response 404 "no such breed")))))))
  (tiny:define-any "*" (req)
    (error-response 404 (format nil "no route for ~a" (tiny:path-info req)))))

(defparameter *app*
  (tiny:pipe *routes* (tiny:wrap-response-content-type "application/json")))

(clack:clackup *app* :server :reactor :use-thread nil)
