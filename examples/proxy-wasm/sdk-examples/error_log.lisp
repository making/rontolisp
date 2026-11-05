;;;; error_log.lisp -- what an error does in a callback under the SDK's default.
;;;;
;;;; The request's path picks the error. The callback answers its default, so the request
;;;; goes on to the upstream instead of being answered here. An :on-delete that signals still
;;;; loses its context: x-deleted reads the last one from the table the SDK keeps them in.

(require :proxy-wasm "../proxy-wasm.lisp")

(defpackage :error-log (:use :cl))

(in-package :error-log)

(defvar *deleted* nil)

(proxy-wasm:define-filter
  (:stream (id root) id)
  (:on-request-headers (stream num-headers end-of-stream)
    (let ((path (proxy-wasm:get-header :request ":path")))
      (cond ((equal path "/failed-call")
             ;; There is no call's reply to write to here.
             (proxy-wasm:set-buffer :call-response-body "x"))
            ((equal path "/signal") (error "not reported"))))
    (proxy-wasm:send-local-response 200
                                    :headers
                                    (list (cons "x-outgoing"
                                                (if (proxy-wasm:http-call
                                                     "no-such-cluster"
                                                     '((":method" . "GET")
                                                       (":path" . "/")
                                                       (":authority" . "x")))
                                                    "sent"
                                                    "refused"))
                                          (cons "x-deleted"
                                                (if (and *deleted*
                                                         (proxy-wasm::%context
                                                          *deleted*))
                                                    "held"
                                                    "gone"))))
    :pause)
  (:on-delete (context)
    (setq *deleted* context)
    (error "not reported")))
