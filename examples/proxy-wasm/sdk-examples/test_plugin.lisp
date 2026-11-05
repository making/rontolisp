;;;; test_plugin.lisp -- a plugin for a host's tests: the request's path picks what it does.
;;;;
;;;; Build it without --no-wasi: /stdout writes to WASI's stdout, which a --no-wasi
;;;; module drops.

(require :proxy-wasm "../proxy-wasm.lisp")

(defpackage :test-plugin (:use :cl))

(in-package :test-plugin)

(defvar *configuration* "")

(defclass plugin-stream () ((path :initform "" :accessor path)))

(defun spin () (loop))

;; Linear memory, which is what a host's memory limit bounds: a Lisp vector would be
;; on the engine's heap.
(defun hog () (loop (proxy-wasm:alloc (* 1024 1024))))

(defun count-metric (name value)
  (proxy-wasm:increment-metric (proxy-wasm:define-metric :counter name) value))

(defun ascii-upcase (octets)
  (let ((out (make-array (length octets) :element-type '(unsigned-byte 8))))
    (dotimes (i (length octets) out)
      (let ((byte (aref octets i)))
        (setf (aref out i) (if (<= 97 byte 122) (- byte 32) byte))))))

(defun octets-or-empty (buffer)
  (or (proxy-wasm:get-buffer-octets buffer)
      (make-array 0 :element-type '(unsigned-byte 8))))

;; Keeps the buffer's length unless the plugin said otherwise: the buffer is read and
;; set back in upper case.
(defun upcase-buffer (buffer size)
  (proxy-wasm:set-buffer buffer (ascii-upcase (octets-or-empty buffer))
                         :size size))

(defun little-endian (octets)
  (let ((value 0))
    (dotimes (i (length octets) value)
      (setq value (+ value (ash (aref octets i) (* 8 i)))))))

(defmacro path-case (stream &body clauses)
  (let ((path (gensym "PATH")))
    `(let ((,path (path ,stream)))
       (cond
        ,@(mapcar
           (lambda (clause)
             (let ((test (car clause)))
               `(,(cond ((eq test t) t)
                        ((listp test)
                         `(or ,@(mapcar (lambda (p) `(equal ,path ,p)) test)))
                        (t `(equal ,path ,test))) ,@(cdr clause))))
           clauses)))))

(proxy-wasm:define-filter
  ;; A failure takes the VM down, as an `unwrap` in the Rust SDK does.
  (:on-failure :trap)
  (:stream (id root) (make-instance 'plugin-stream))
  (:on-configure (root size)
    (setf *configuration* (or (proxy-wasm:get-buffer :plugin-configuration) ""))
    (not (equal *configuration* "refuse")))
  (:on-request-headers (stream count end-of-stream)
    (setf (path stream) (or (proxy-wasm:get-header :request ":path") ""))
    (path-case stream ("/spin-request" (spin))
     ("/trap-request" (proxy-wasm:panic))
     ("/host" (proxy-wasm:set-header :request "host" "elsewhere") :continue)
     ("/request-edit" (proxy-wasm:set-header :request "x-plugin" "request")
      :continue)
     ("/request-end"
      (proxy-wasm:set-header :request "x-end" (if end-of-stream "yes" "no"))
      :continue)
     ("/request-pairs"
      (proxy-wasm:set-headers :request (append (remove-if
                                                (lambda (pair)
                                                  (equal (car pair) "x-gone"))
                                                (proxy-wasm:get-headers
                                                 :request))
                                               '(("x-pairs" . "set"))))
      :continue)
     ("/local"
      (proxy-wasm:send-local-response 403
       :headers '(("x-plugin" . "local") ("content-length" . "1"))
       :body (format nil "denied~%")) :pause)
     (("/request-replace" "/request-stream" "/request-then-trap")
      (proxy-wasm:remove-header :request "content-length") :continue)
     ("/stdout" (write-line "to stdout") :continue)
     ("/pairs-malformed"
      ;; a count no bytes follow
      (proxy-wasm::%proxy-set-header-map-pairs 0
                                               (make-array 4
                                                :element-type '(unsigned-byte 8)
                                                :initial-contents
                                                '(255 255 255 127))) :continue)
     (t :continue)))
  (:on-request-body (stream size end-of-stream)
    (path-case stream
               ("/request-read"
                (let ((octets (proxy-wasm:get-buffer-octets :request-body)))
                  (when octets
                    (count-metric "body.bytes;kind=request" (length octets))))
                :continue)
               ("/request-replace"
                (cond ((not end-of-stream) :pause)
                      (t
                       (upcase-buffer :request-body size)
                       :continue)))
               (("/request-stream" "/request-then-trap")
                (upcase-buffer :request-body size) :continue)
               ("/local-body"
                (when end-of-stream
                  (proxy-wasm:send-local-response 451
                   :body (format nil "read it~%"))) :pause)
               ("/request-trap" (proxy-wasm:panic)) (t :continue)))
  (:on-response-headers (stream count end-of-stream)
    (path-case stream ("/spin-head" (spin)) ("/trap-head" (proxy-wasm:panic))
     ("/hog-head" (hog)) ("/local-late" (proxy-wasm:send-local-response 403))
     ("/framing" (proxy-wasm:set-header :response "transfer-encoding" "x"))
     ("/smuggle"
      (proxy-wasm:set-header :response "x-plugin"
       (format nil "a~c~cx-smuggled: 1" (code-char 13) (code-char 10))))
     ("/edit" (proxy-wasm:add-header :response "x-plugin" "edited"))
     ("/failed-call" (proxy-wasm:set-buffer :call-response-body "x"))
     ("/signal" (error "not handled"))
     ("/hog-fields"
      (loop
        (proxy-wasm:add-header :response "x-plugin"
                               "a value kept by the host")))
     ("/meters"
      (dotimes (i 100) (count-metric (format nil "plugin.meters;i=~a" i) 1)))
     ("/properties"
      (let ((route (or (proxy-wasm:get-property "route_name") ""))
            (code
             (little-endian
              (proxy-wasm:get-property-octets "response" "code"))))
        (proxy-wasm:add-header :response "x-route" route)
        (proxy-wasm:add-header :response "x-code" (princ-to-string code))
        (proxy-wasm:add-header :response "x-configuration" *configuration*)))
     ("/outgoing"
      (proxy-wasm:add-header :response "x-outgoing"
                             (if (proxy-wasm:http-call "upstream" '()
                                                       :timeout 1000)
                                 "sent"
                                 "refused")))
     ("/replace" (proxy-wasm:remove-header :response "content-length")))
    :continue)
  (:on-response-body (stream size end-of-stream)
    (path-case stream ("/spin-body" (spin)) ("/trap-body" (proxy-wasm:panic))
               ("/hog-body" (hog))
               ("/count"
                (let ((octets (proxy-wasm:get-buffer-octets :response-body)))
                  (when octets
                    (count-metric "body.bytes;kind=response" (length octets))))
                :continue)
               (("/replace-sized" "/replace")
                (upcase-buffer :response-body size)
                (when end-of-stream
                  (proxy-wasm:set-buffer :response-body "<end>"
                                         :start #xFFFFFFFF
                                         :size 0)) :continue)
               ("/hold" (if end-of-stream :continue :pause))
               ("/request-then-trap" (proxy-wasm:panic))
               ("/replace-trap"
                (proxy-wasm:set-buffer :response-body "PART" :size size)
                (proxy-wasm:panic)) (t :continue))))
