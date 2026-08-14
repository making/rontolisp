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

;; Awaiting needs an async frame, so the response is built in one -- and the
;; application below just hands its FUTURE over, which the reactor transport
;; resolves at the boundary. ../dog-fetcher is shaped this way because
;; tiny-routes generates its route bodies as plain lambdas, where `await' is a
;; compile error; here it is a choice, and the reason to make it is that
;; anything WRAPPING the application (Clack middleware, a router) would
;; otherwise be handed the future where it expects the response list.
(rontolisp:async-defun ticker-response ()
  (let ((price (rontolisp:await (ticker))))
    (if price
        (json-response 200
         (rontolisp:plist-hash-table (list :pair "BTC/JPY" :price price)))
        (json-response 502
                       (rontolisp:plist-hash-table
                        (list :error "the bitFlyer API did not answer"))))))

;; A Clack application is a function of the request environment. This one
;; ignores it: there is one endpoint and it takes no arguments.
(defun app (env)
  (declare (ignore env))
  (ticker-response))

(clack:clackup #'app :server :rontolisp :port 8080 :use-thread nil)
