;;; The hello world of the ENVELOPE boundary: one outgoing request, one JSON
;;; answer. Ask bitFlyer what a bitcoin costs in yen, and say so.
;;;
;;; Every body here is a DOCUMENT -- a few hundred bytes of JSON in, a few
;;; dozen out -- so nothing is gained by taking one out of the envelope and
;;; streaming it, and build.sh says exactly that with --host-boundary=envelope.
;;; What that buys is not size (the two boundaries land within about 1% of each
;;; other, either way round) but the BOUNDARY: the module imports one host
;;; function and the host keeps no state at all, so both halves of it are fixed
;;; by the transport and src/index.js is three lines. ../dog-fetcher is the same
;;; program shape on the streaming boundary -- read the two together.
;;;
;;; :server :rontolisp picks the transport per target at read time, so THIS ONE
;;; SOURCE runs on every backend (the README has the commands).

(ql:quickload "clack")

;; bitFlyer answers {"product_code": "BTC_JPY", "ltp": 16000000.0, ...} --
;; `ltp' is the last traded price, which is what "the price" means here.
(rontolisp:async-defun ticker ()
  (let* ((res
          (rontolisp:await
           (rontolisp:fetch
            "https://api.bitflyer.com/v1/ticker?product_code=BTC_JPY")))
         (body (rontolisp:await (rontolisp:read-all (getf res :body)))))
    (if (eql (getf res :status) 200)
        (gethash "ltp" (rontolisp:json-parse body))
        nil)))

(defun json-response (status obj)
  (list status '(:content-type "application/json")
        (list (format nil "~a~%" (rontolisp:json-stringify obj)))))

;; A Clack application is a function of the request environment. This one
;; ignores it: there is one endpoint and it takes no arguments.
;;
;; It is an ASYNC-DEFUN, and that has nothing to do with the boundary -- the
;; same source compiles on either one, and on the interpreter, the JVM and
;; --component. What asks for it is `await': only an async frame may await, and
;; this application has to look AT the price to choose 200 or 502. So it awaits,
;; so it is an async-defun, and what it answers is a FUTURE of the response --
;; which every rontolisp transport resolves at its boundary (the reactor's
;; %http-reactor-handle, the socket transports' %http-serve-request, wasmtime
;; serve under --component).
;;
;; The other shape keeps `app' an ordinary Clack function and puts the await one
;; level down, returning that helper's future:
;;
;;   (rontolisp:async-defun answer (env) ... (rontolisp:await (ticker)) ...)
;;   (defun app (env) (answer env))
;;
;; Prefer it as soon as anything WRAPS the application -- Clack middleware, a
;; router -- because a wrapper that inspects the response list would be handed a
;; future instead. ../dog-fetcher is written that way for exactly that reason:
;; its routes are composed by tiny-routes. Here nothing wraps `app', so the
;; await stays where it reads best.
(rontolisp:async-defun app (env)
  (declare (ignore env))
  (let ((price (rontolisp:await (ticker))))
    (if price
        (json-response 200
         (rontolisp:plist-hash-table (list :pair "BTC/JPY" :price price)))
        (json-response 502
                       (rontolisp:plist-hash-table
                        (list :error "the bitFlyer API did not answer"))))))

(clack:clackup #'app :server :rontolisp :port 8080 :use-thread nil)
