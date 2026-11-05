;;;; error_log.lisp -- what an error does in a callback under the SDK's default.
;;;;
;;;; The request's path picks the error. The callback answers its default, so the request
;;;; goes on to the upstream instead of being answered here.

(require :proxy-wasm "../proxy-wasm.lisp")

(defpackage :error-log (:use :cl))

(in-package :error-log)

(defmethod proxy-wasm:on-request-headers
    ((stream proxy-wasm:http-context) num-headers end-of-stream)
  (declare (ignore num-headers end-of-stream))
  (let ((path (proxy-wasm:get-header :request ":path")))
    (cond ((equal path "/failed-call")
           ;; There is no call's reply to write to here.
           (proxy-wasm:set-buffer :call-response-body "x"))
          ((equal path "/signal") (error "not reported"))))
  (proxy-wasm:send-local-response 200
                                  :headers
                                  (list
                                   (cons "x-outgoing"
                                         (if (proxy-wasm:http-call
                                              "no-such-cluster"
                                              '((":method" . "GET")
                                                (":path" . "/")
                                                (":authority" . "x")))
                                             "sent"
                                             "refused"))))
  :pause)
