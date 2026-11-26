;;; A Worker that RELAYS: every request is forwarded to dog.ceo and the reply
;;; -- status, content type and body -- is streamed back to the client as it
;;; arrives, a chunk at a time. ../dog-fetcher parses the upstream's answer and
;;; builds its own; this one hands it through. Two things about that shape
;;; decide the build (build.sh):
;;;
;;;   --host-boundary=streaming  the reply body is RELAYED: the transport
;;;                              forwards each chunk the moment it is pulled,
;;;                              so a 50 KB breed listing never exists whole in
;;;                              linear memory. The default envelope would hold
;;;                              it before answering.
;;;   --reentrant                a relay is one upstream round trip of parked
;;;                              time and almost no CPU, so serialising the
;;;                              calls costs the whole width: N concurrent
;;;                              clients would each wait for the N-1 relays
;;;                              ahead of them. --reentrant lets them overlap
;;;                              on ONE instance; every body import then
;;;                              carries a call id, so each pull names the
;;;                              relay it belongs to.
;;;
;;; The client is rontolisp:fetch, THE SAME (await (fetch ...)) that runs on
;;; the interpreter, the JVM and a wasi:http component; --host-fetch lowers it
;;; onto the Worker runtime's own fetch. :server :rontolisp picks the transport
;;; per target at read time, so THIS ONE SOURCE runs on every backend (the
;;; README has the commands).

(ql:quickload '("clack" "tiny-routes/lite"))

(defun json-response (status obj)
  (list status '(:content-type "application/json")
        (list (format nil "~a~%" (rontolisp:json-stringify obj)))))

(defun error-response (status message)
  (json-response status (rontolisp:plist-hash-table (list :error message))))

;; The relay. The reply's :body is a STREAM and it is answered AS the response
;; body: nothing here reads it -- the transport pulls it chunk by chunk
;; (env.readResponseBody) and pushes each chunk out (env.writeResponseBody), so
;; the upstream's answer is on its way to the client while the rest of it is
;; still on the wire. The upstream's status and content type pass through with
;; it; a transport error before the head is the one case answered here.
(rontolisp:async-defun relay (path)
  (handler-case (let ((res
                       (rontolisp:await
                        (rontolisp:fetch
                         (concatenate 'string "https://dog.ceo/api" path)))))
                  (list (getf res :status)
                        (list :content-type
                              (or (cdr
                                   (assoc "content-type" (getf res :headers)
                                          :test #'string-equal))
                                  "application/octet-stream"))
                        (getf res :body)))
    (error () (error-response 502 "the dog API did not answer"))))

;;; --- the routes --------------------------------------------------------------

;; A breed reaches the upstream inside a URL, so it is checked first. nil
;; DECLINES the route, which drops the request into the catch-all 404.
(defun valid-breed (breed)
  (and (plusp (length breed))
       (every (lambda (c) (or (alpha-char-p c) (eql c #\-))) breed) breed))

;; The route bodies are synchronous (tiny-routes composes plain functions), so
;; they return the async-defun's FUTURE and the reactor transport resolves it
;; at the boundary.
(tiny:define-routes *routes*
  (tiny:define-get "/" () (relay "/breeds/list/all"))
  (tiny:define-get "/breed/:breed" (req)
    (let ((breed
           (valid-breed (string-downcase (tiny:path-parameter req :breed)))))
      (when breed (relay (format nil "/breed/~a/images" breed)))))
  (tiny:define-any "*" (req)
    (error-response 404 (format nil "no route for ~a" (tiny:path-info req)))))

(clack:clackup *routes*
               :server :rontolisp
               :address "0.0.0.0"
               :port 8080
               :use-thread nil)
