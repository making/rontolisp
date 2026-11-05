;;;; proxy-wasm.lisp -- a Proxy-Wasm SDK written from scratch, in Lisp.
;;;;
;;;; Proxy-Wasm (https://github.com/proxy-wasm/spec) is the ABI an Envoy filter
;;;; speaks to its proxy. A filter is a core WebAssembly module: it EXPORTS the
;;;; proxy_on_* callbacks the proxy calls, and it IMPORTS the proxy_* functions it
;;;; calls back. This file is everything between that ABI (v0.2.1) and a filter
;;;; written with CLOS:
;;;;
;;;;   (defclass my-root (proxy-wasm:root-context) ())
;;;;   (defmethod proxy-wasm:on-request-headers
;;;;       ((ctx proxy-wasm:http-context) num-headers end-of-stream)
;;;;     (proxy-wasm:set-header :request "x-from-lisp" "hello")
;;;;     :continue)
;;;;   (proxy-wasm:set-root-context (lambda (id) (make-instance 'my-root :id id)))
;;;;
;;;; Covered: logging, the clock, ticks, header maps, body buffers, local responses,
;;;; outgoing HTTP calls, shared data, metrics and properties. Not covered: gRPC
;;;; calls, shared queues, foreign functions and the TCP (network filter) callbacks.
;;;;
;;;; The one thing this ABI asks that a boundary type cannot state is the
;;;; OUT-POINTER: proxy_get_header_map_value(map, key, key_len, char **value,
;;;; size_t *value_len) answers by writing two addresses into memory the caller
;;;; reserved, the bytes behind them coming from an allocator the host calls back
;;;; (proxy_on_memory_allocate). So the SDK binds the module's own linear memory
;;;; through the reserved import module "rontolisp:memory".

(defpackage :proxy-wasm
  (:use :cl)
  (:export ;; contexts and the callbacks a filter specializes
           :context :context-id
           :root-context :http-context
           :context-root :set-root-context
           :make-http-context :on-vm-start
           :on-configure :on-tick
           :on-request-headers :on-request-body
           :on-request-trailers :on-response-headers
           :on-response-body :on-response-trailers
           :on-http-call-response :on-done
           :on-log :on-delete
           ;; host functions
           :log-trace :log-debug
           :log-info :log-warn
           :log-error :log-critical
           :current-time-nanos :set-tick-period
           :heap-top :panic
           :get-header :get-headers
           :set-header :add-header
           :remove-header :set-headers
           :get-buffer :get-buffer-octets
           :buffer-size :set-buffer
           :continue-request :continue-response
           :send-local-response :http-call
           :shared-data :set-shared-data
           :define-metric :increment-metric
           :record-metric :metric-value
           :get-property :get-property-octets :set-property))

(in-package :proxy-wasm)

;;; ---------------------------------------------------------------------------
;;; The module's own linear memory
;;; ---------------------------------------------------------------------------

(rontolisp:wasm-import '%alloc
                       :from "rontolisp:memory"
                       :as "alloc"
                       :params '(:u32)
                       :returns :u32)
(rontolisp:wasm-import '%mark :from "rontolisp:memory" :as "mark" :returns :u32)
(rontolisp:wasm-import '%reset
                       :from "rontolisp:memory"
                       :as "reset"
                       :params '(:u32))
(rontolisp:wasm-import '%load32
                       :from "rontolisp:memory"
                       :as "load32"
                       :params '(:u32)
                       :returns :u32)
(rontolisp:wasm-import '%load64
                       :from "rontolisp:memory"
                       :as "load64"
                       :params '(:u32)
                       :returns :s64)
(rontolisp:wasm-import '%memory-string
                       :from "rontolisp:memory"
                       :as "string"
                       :params '(:u32 :u32)
                       :returns :string)
(rontolisp:wasm-import '%memory-octets
                       :from "rontolisp:memory"
                       :as "octets"
                       :params '(:u32 :u32)
                       :returns :octets)

;;; ---------------------------------------------------------------------------
;;; The proxy_* host functions, one directive per ABI function
;;; ---------------------------------------------------------------------------
;;; A :string parameter is (ptr, len) in one Lisp argument. An :octets one is too,
;;; and takes an (unsigned-byte 8) vector or a string (its UTF-8 encoding), which is
;;; what a header value or a body may be. An address, a size or an id the host hands
;;; out is :u32, because it can pass the 31-bit range of :s32 arguments: a resident
;;; VM's context ids only ever count up. An enumeration or a status stays :s32.

(rontolisp:wasm-import '%proxy-log
                       :from "env"
                       :as "proxy_log"
                       :params '(:s32 :string)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-get-current-time-nanoseconds
                       :from "env"
                       :as "proxy_get_current_time_nanoseconds"
                       :params '(:u32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-set-tick-period-milliseconds
                       :from "env"
                       :as "proxy_set_tick_period_milliseconds"
                       :params '(:u32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-get-buffer-status
                       :from "env"
                       :as "proxy_get_buffer_status"
                       :params '(:s32 :u32 :u32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-get-buffer-bytes
                       :from "env"
                       :as "proxy_get_buffer_bytes"
                       :params '(:s32 :u32 :u32 :u32 :u32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-set-buffer-bytes
                       :from "env"
                       :as "proxy_set_buffer_bytes"
                       :params '(:s32 :u32 :u32 :octets)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-get-header-map-pairs
                       :from "env"
                       :as "proxy_get_header_map_pairs"
                       :params '(:s32 :u32 :u32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-set-header-map-pairs
                       :from "env"
                       :as "proxy_set_header_map_pairs"
                       :params '(:s32 :octets)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-get-header-map-value
                       :from "env"
                       :as "proxy_get_header_map_value"
                       :params '(:s32 :string :u32 :u32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-add-header-map-value
                       :from "env"
                       :as "proxy_add_header_map_value"
                       :params '(:s32 :string :octets)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-replace-header-map-value
                       :from "env"
                       :as "proxy_replace_header_map_value"
                       :params '(:s32 :string :octets)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-remove-header-map-value
                       :from "env"
                       :as "proxy_remove_header_map_value"
                       :params '(:s32 :string)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-set-effective-context
                       :from "env"
                       :as "proxy_set_effective_context"
                       :params '(:u32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-continue-stream
                       :from "env"
                       :as "proxy_continue_stream"
                       :params '(:s32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-send-local-response
                       :from "env"
                       :as "proxy_send_local_response"
                       :params '(:s32 :string :octets :octets :s32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-http-call
                       :from "env"
                       :as "proxy_http_call"
                       :params '(:string :octets :octets :octets :u32 :u32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-set-shared-data
                       :from "env"
                       :as "proxy_set_shared_data"
                       :params '(:string :octets :u32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-get-shared-data
                       :from "env"
                       :as "proxy_get_shared_data"
                       :params '(:string :u32 :u32 :u32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-define-metric
                       :from "env"
                       :as "proxy_define_metric"
                       :params '(:s32 :string :u32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-increment-metric
                       :from "env"
                       :as "proxy_increment_metric"
                       :params '(:u32 :s64)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-record-metric
                       :from "env"
                       :as "proxy_record_metric"
                       :params '(:u32 :s64)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-get-metric
                       :from "env"
                       :as "proxy_get_metric"
                       :params '(:u32 :u32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-get-property
                       :from "env"
                       :as "proxy_get_property"
                       :params '(:string :u32 :u32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-set-property
                       :from "env"
                       :as "proxy_set_property"
                       :params '(:string :octets)
                       :returns :s32)

;;; ---------------------------------------------------------------------------
;;; Status codes and the ABI's enumerations
;;; ---------------------------------------------------------------------------

(defconstant +status-ok+ 0)
(defconstant +status-not-found+ 1)
(defconstant +status-empty+ 7)
(defconstant +status-cas-mismatch+ 8)

;; A wasm trap: no handler sees it, and it takes the whole VM down. The address is past
;; the end of any linear memory that is not the full 4GiB.
(defun panic () (%load32 #xFFFFFFFC))

;; What a failure does is decided while the filter compiles, because a run-time choice
;; would keep the handler in every module. Unless the program declares the feature
;; :proxy-wasm-trap, an error is handled in the callback (%with-callback).
(defmacro %trap-or-handle (trap handle)
  (if (member :proxy-wasm-trap *features*) trap handle))

;; A host call that only answers OK or fails. Anything else is a defect in the call: the
;; reason goes to the log here, where it is known. Printing a condition in the callback
;; instead would cost every filter the condition printer.
(defun %check (operation status)
  (unless (= status +status-ok+)
    (%proxy-log 4
     (format nil "proxy-wasm: ~a answered status ~a" operation status))
    (%trap-or-handle (panic) (error "host call failed")))
  nil)

;; For a call that reads: T for OK, NIL for "there is no such thing here".
(defun %found-p (operation status)
  (or (= status +status-not-found+) (= status +status-empty+)
      (progn
        (%check operation status)
        t))
  (= status +status-ok+))

(defun %map-type (map)
  (ecase map
    (:request 0)
    (:request-trailers 1)
    (:response 2)
    (:response-trailers 3)
    (:call-response 6)
    (:call-response-trailers 7)))

(defun %buffer-type (buffer)
  (ecase buffer
    (:request-body 0)
    (:response-body 1)
    (:call-response-body 4)
    (:vm-configuration 6)
    (:plugin-configuration 7)))

(defun %metric-type (type) (ecase type (:counter 0) (:gauge 1) (:histogram 2)))

;;; ---------------------------------------------------------------------------
;;; Out-pointers
;;; ---------------------------------------------------------------------------
;;; Nothing here frees a cell or the bytes the host answered into it: the whole bump
;;; heap goes back to where it stood when the host entered the module, at the end of
;;; that callback (%with-callback). So a filter's memory stays flat no matter how
;;; many headers it reads, and every value it keeps is a Lisp string the engine owns.

(defun %cell-string (cells)
  (%memory-string (%load32 cells) (%load32 (+ cells 4))))

(defun %cell-octets (cells)
  (%memory-octets (%load32 cells) (%load32 (+ cells 4))))

;; The top of the bump heap, for a filter that wants to watch that it stays flat.
(defun heap-top () (%mark))

;;; ---------------------------------------------------------------------------
;;; Logging, the clock, ticks
;;; ---------------------------------------------------------------------------

;; A call whose control string is a literal has it compiled at the call site, which leaves
;; the run-time FORMAT interpreter out of the module. Any other call, and #'LOG-INFO,
;; reach the function, and the interpreter with it.
(defun %log (level control args)
  (%proxy-log level (apply #'format nil control args))
  nil)

(defmacro %define-log (name level)
  `(progn
     (defun ,name (control &rest args) (%log ,level control args))
     (define-compiler-macro ,name (&whole form control &rest args)
       (if (stringp control)
           `(progn
              (%proxy-log ,,level (format nil ,control ,@args))
              nil)
           form))))

(%define-log log-trace 0)
(%define-log log-debug 1)
(%define-log log-info 2)
(%define-log log-warn 3)
(%define-log log-error 4)
(%define-log log-critical 5)

(defun current-time-nanos ()
  (let ((cell (%alloc 8)))
    (%check "proxy_get_current_time_nanoseconds"
            (%proxy-get-current-time-nanoseconds cell))
    (%load64 cell)))

(defun set-tick-period (milliseconds)
  (%check "proxy_set_tick_period_milliseconds"
          (%proxy-set-tick-period-milliseconds milliseconds)))

;;; ---------------------------------------------------------------------------
;;; Header maps
;;; ---------------------------------------------------------------------------
;;; MAP is one of :request :request-trailers :response :response-trailers
;;; :call-response :call-response-trailers.

(defun get-header (map name)
  (let ((cells (%alloc 8)))
    (when (%found-p "proxy_get_header_map_value"
           (%proxy-get-header-map-value (%map-type map) name cells (+ cells 4)))
      (%cell-string cells))))

(defun set-header (map name value)
  (%check "proxy_replace_header_map_value"
          (%proxy-replace-header-map-value (%map-type map) name value)))

(defun add-header (map name value)
  (%check "proxy_add_header_map_value"
          (%proxy-add-header-map-value (%map-type map) name value)))

(defun remove-header (map name)
  (%check "proxy_remove_header_map_value"
          (%proxy-remove-header-map-value (%map-type map) name)))

;; The serialized form the ABI uses for a whole map, little-endian:
;;   u32 count, (u32 name-size, u32 value-size) * count, (name NUL value NUL) * count
(defun %parse-pairs (address size)
  (when (plusp size)
    (let* ((count (%load32 address))
           (sizes (+ address 4))
           (data (+ sizes (* 8 count)))
           (pairs '()))
      (dotimes (i count)
        (let ((name-size (%load32 (+ sizes (* 8 i))))
              (value-size (%load32 (+ sizes (* 8 i) 4))))
          (push (cons (%memory-string data name-size)
                      (%memory-string (+ data name-size 1) value-size)) pairs)
          (setq data (+ data name-size value-size 2))))
      (nreverse pairs))))

(defun %octets-of (value)
  (if (stringp value) (rontolisp:string-to-octets value) value))

(defun %store-u32 (octets index value)
  (dotimes (byte 4)
    (setf (aref octets (+ index byte)) (logand (ash value (* -8 byte)) 255))))

(defun %serialize-pairs (pairs)
  (let* ((encoded
          (mapcar (lambda (pair)
                    (cons (%octets-of (car pair)) (%octets-of (cdr pair))))
                  pairs))
         (data-size
          (reduce #'+
                  (mapcar
                   (lambda (pair) (+ (length (car pair)) (length (cdr pair)) 2))
                   encoded)))
         (out
          (make-array (+ 4 (* 8 (length encoded)) data-size)
                      :element-type '(unsigned-byte 8)
                      :initial-element 0))
         (sizes 4)
         (data (+ 4 (* 8 (length encoded)))))
    (%store-u32 out 0 (length encoded))
    (dolist (pair encoded)
      (%store-u32 out sizes (length (car pair)))
      (%store-u32 out (+ sizes 4) (length (cdr pair)))
      (setq sizes (+ sizes 8))
      (replace out (car pair) :start1 data)
      (setq data (+ data (length (car pair)) 1))
      (replace out (cdr pair) :start1 data)
      (setq data (+ data (length (cdr pair)) 1)))
    out))

(defun get-headers (map)
  (let ((cells (%alloc 8)))
    (when (%found-p "proxy_get_header_map_pairs"
           (%proxy-get-header-map-pairs (%map-type map) cells (+ cells 4)))
      (%parse-pairs (%load32 cells) (%load32 (+ cells 4))))))

(defun set-headers (map pairs)
  (%check "proxy_set_header_map_pairs"
   (%proxy-set-header-map-pairs (%map-type map) (%serialize-pairs pairs))))

;;; ---------------------------------------------------------------------------
;;; Body and configuration buffers
;;; ---------------------------------------------------------------------------
;;; BUFFER is one of :request-body :response-body :call-response-body
;;; :vm-configuration :plugin-configuration.

(defun buffer-size (buffer)
  (let ((cells (%alloc 8)))
    (if (%found-p "proxy_get_buffer_status"
         (%proxy-get-buffer-status (%buffer-type buffer) cells (+ cells 4)))
        (%load32 cells)
        0)))

(defun %read-buffer (buffer lift)
  (let ((size (buffer-size buffer)) (cells (%alloc 8)))
    (when (and (plusp size)
               (%found-p "proxy_get_buffer_bytes"
                         (%proxy-get-buffer-bytes (%buffer-type buffer) 0 size
                                                  cells (+ cells 4))))
      (funcall lift cells))))

(defun get-buffer (buffer) (%read-buffer buffer #'%cell-string))

(defun get-buffer-octets (buffer) (%read-buffer buffer #'%cell-octets))

;; Replaces SIZE bytes from START, by default the whole buffer. A START past the end
;; appends.
(defun set-buffer (buffer value &key (start 0) size)
  (%check "proxy_set_buffer_bytes"
          (%proxy-set-buffer-bytes (%buffer-type buffer) start
                                   (or size (buffer-size buffer)) value)))

;;; ---------------------------------------------------------------------------
;;; The stream: resuming it, answering it, calling out
;;; ---------------------------------------------------------------------------

(defun continue-request ()
  (%check "proxy_continue_stream" (%proxy-continue-stream 0)))

(defun continue-response ()
  (%check "proxy_continue_stream" (%proxy-continue-stream 1)))

;; Answer the stream from here. The callback making this call returns :pause.
(defun send-local-response
    (status &key (details "") (body "") headers (grpc-status -1))
  (%check "proxy_send_local_response"
          (%proxy-send-local-response status details body
                                      (%serialize-pairs headers) grpc-status)))

;; The context whose callback is running, and which context made each outgoing call.
;; The ABI hands a call's reply to the PLUGIN's context whichever stream made the
;; call, so getting it back to that stream is this SDK's job.
(defvar *current-context* nil)

;; A table keyed by a host-issued integer: buckets of an alist. A host's ids and tokens count
;; up, so the buckets fill evenly. It costs a filter 4KB less than a hash table.
(defun %table () (make-array 64 :initial-element nil))

(defun %lookup (table key) (cdr (assoc key (aref table (logand key 63)))))

(defun %store (table key value)
  (let ((index (logand key 63)))
    (setf (aref table index) (cons (cons key value) (aref table index)))))

(defun %forget (table key)
  (let ((index (logand key 63)) (kept '()))
    (dolist (entry (aref table index))
      (unless (= (car entry) key) (push entry kept)))
    (setf (aref table index) kept)))

(defvar *callouts* (%table))

;; HEADERS must carry :method, :path and :authority. Answers the call's token, or NIL when
;; the host refuses the call (an unknown cluster, say), which a filter can act on under
;; either error mode. The reply arrives at ON-HTTP-CALL-RESPONSE on the context that made
;; the call.
(defun http-call (cluster headers &key (body "") trailers (timeout 5000))
  (let ((cell (%alloc 8)))
    (when (= +status-ok+
             (%proxy-http-call cluster (%serialize-pairs headers) body
                               (%serialize-pairs trailers) timeout cell))
      (%store *callouts* (%load32 cell) *current-context*)
      (%load32 cell))))

;;; ---------------------------------------------------------------------------
;;; Shared data, metrics, properties
;;; ---------------------------------------------------------------------------

;; Answers the value and its compare-and-swap version, or NIL when the key is absent.
(defun shared-data (key)
  (let ((cells (%alloc 12)))
    (if (%found-p "proxy_get_shared_data"
                  (%proxy-get-shared-data key cells (+ cells 4) (+ cells 8)))
        (values (%cell-string cells) (%load32 (+ cells 8)))
        (values nil 0))))

;; Answers T, or NIL when CAS (when nonzero) names a version that has moved on.
(defun set-shared-data (key value &optional (cas 0))
  (let ((status (%proxy-set-shared-data key value cas)))
    (unless (= status +status-cas-mismatch+)
      (%check "proxy_set_shared_data" status)
      t)))

;; TYPE is :counter, :gauge or :histogram. Answers the id the other three take.
(defun define-metric (type name)
  (let ((cell (%alloc 8)))
    (%check "proxy_define_metric"
            (%proxy-define-metric (%metric-type type) name cell))
    (%load32 cell)))

(defun increment-metric (id &optional (delta 1))
  (%check "proxy_increment_metric" (%proxy-increment-metric id delta)))

(defun record-metric (id value)
  (%check "proxy_record_metric" (%proxy-record-metric id value)))

(defun metric-value (id)
  (let ((cell (%alloc 8)))
    (%check "proxy_get_metric" (%proxy-get-metric id cell))
    (%load64 cell)))

;; A property path is its segments joined by NUL: (get-property "request" "path").
(defun %property-path (segments)
  (let ((path (car segments)))
    (dolist (segment (cdr segments))
      (setq path (concatenate 'string path (string (code-char 0)) segment)))
    path))

;; Answers the cells holding the property's bytes, or NIL when the host has no such
;; property.
(defun %property-cells (segments)
  (let ((cells (%alloc 8)))
    (when (%found-p "proxy_get_property"
           (%proxy-get-property (%property-path segments) cells (+ cells 4)))
      cells)))

(defun get-property (&rest segments)
  (let ((cells (%property-cells segments))) (when cells (%cell-string cells))))

;; For a numeric property, which is its raw little-endian bytes.
(defun get-property-octets (&rest segments)
  (let ((cells (%property-cells segments))) (when cells (%cell-octets cells))))

(defun set-property (value &rest segments)
  (%check "proxy_set_property"
          (%proxy-set-property (%property-path segments) value)))

;;; ---------------------------------------------------------------------------
;;; Contexts: what a filter specializes
;;; ---------------------------------------------------------------------------

(defclass context () ((id :initarg :id :reader context-id)))

;; One per plugin configuration (and one for the VM itself) on every worker thread.
(defclass root-context (context) ())

;; One per HTTP stream.
(defclass http-context (context) ((root :initarg :root :reader context-root)))

(defgeneric make-http-context (root id)
  (:method ((root root-context) id)
           (make-instance 'http-context :id id :root root)))

;; Root callbacks. A boolean result says whether the VM or the plugin may start.
(defgeneric on-vm-start (root configuration-size)
  (:method ((root root-context) configuration-size)
           (declare (ignore configuration-size)) t))

(defgeneric on-configure (root configuration-size)
  (:method ((root root-context) configuration-size)
           (declare (ignore configuration-size)) t))

(defgeneric on-tick (root) (:method ((root root-context)) nil))

;; Stream callbacks answer :continue or :pause.
(defgeneric on-request-headers (context num-headers end-of-stream)
  (:method ((context http-context) num-headers end-of-stream)
           (declare (ignore num-headers end-of-stream)) :continue))

(defgeneric on-request-body (context body-size end-of-stream)
  (:method ((context http-context) body-size end-of-stream)
           (declare (ignore body-size end-of-stream)) :continue))

(defgeneric on-request-trailers (context num-trailers)
  (:method ((context http-context) num-trailers) (declare (ignore num-trailers))
           :continue))

(defgeneric on-response-headers (context num-headers end-of-stream)
  (:method ((context http-context) num-headers end-of-stream)
           (declare (ignore num-headers end-of-stream)) :continue))

(defgeneric on-response-body (context body-size end-of-stream)
  (:method ((context http-context) body-size end-of-stream)
           (declare (ignore body-size end-of-stream)) :continue))

(defgeneric on-response-trailers (context num-trailers)
  (:method ((context http-context) num-trailers) (declare (ignore num-trailers))
           :continue))

;; On the context that made the call: a stream's, or a root's.
(defgeneric on-http-call-response
    (context token num-headers body-size num-trailers)
  (:method ((context context) token num-headers body-size num-trailers)
           (declare (ignore token num-headers body-size num-trailers)) nil))

;; A boolean: whether the context is finished. Any context.
(defgeneric on-done (context) (:method ((context context)) t))

(defgeneric on-log (context) (:method ((context context)) nil))

(defgeneric on-delete (context) (:method ((context context)) nil))

;;; ---------------------------------------------------------------------------
;;; The proxy_on_* exports
;;; ---------------------------------------------------------------------------

(defvar *root-context-factory*
  (lambda (id) (make-instance 'root-context :id id)))

(defvar *contexts* (%table))

;; FACTORY takes a context id and answers a ROOT-CONTEXT.
(defun set-root-context (factory) (setf *root-context-factory* factory))

;; What every callback runs inside. A Lisp error must not become a wasm trap, which would
;; take the whole VM down: it is logged and the callback answers FALLBACK. The log says
;; only that it happened, since the report is the expensive part: a filter that wants it
;; catches the condition itself. Under :proxy-wasm-trap there is no handler, and the error
;; does trap. On the way out the bump heap pops back to where it stood, releasing every
;; out-cell and every byte the host answered during the call.
(defmacro %with-callback ((fallback) &body body)
  (let ((mark (gensym "MARK")) (result (gensym "RESULT")))
    `(let* ((,mark (%mark))
            (,result
             (%trap-or-handle (progn ,@body)
                              (handler-case (progn ,@body)
                                (error ()
                                  (%proxy-log 4
                                   "proxy-wasm: a callback signaled an error")
                                  ,fallback)))))
       (%reset ,mark)
       ,result)))

;; An export, with the context its first argument names resolved. A callback for a
;; context the module does not hold answers FALLBACK.
(defmacro %define-callback (name export (context &rest params) (&rest types)
                                 returns fallback &body body)
  (let ((id (gensym "ID")))
    `(progn
       (defun ,name (,id ,@params)
         (%with-callback (,fallback)
                         (let ((,context (%lookup *contexts* ,id)))
                           (cond
                            (,context
                             (setq *current-context* ,context)
                             ,@body)
                            (t ,fallback)))))
       (rontolisp:wasm-export ',name
                              :as ,export
                              :params '(:u32 ,@types)
                              :returns ,returns))))

(defun %action (action) (if (eq action :pause) 1 0))

;; The export whose presence tells the host which ABI this module speaks.
(defun %abi-version () nil)
(rontolisp:wasm-export '%abi-version :as "proxy_abi_version_0_2_1")

(defun %on-memory-allocate (size) (%alloc size))
(rontolisp:wasm-export '%on-memory-allocate
                       :as "proxy_on_memory_allocate"
                       :params '(:u32)
                       :returns :u32)

;; A parent of 0 makes a root context. Every other context is a stream of its parent.
(defun %on-context-create (id parent)
  (%with-callback (nil)
                  (%store *contexts* id
                          (if (= parent 0)
                              (funcall *root-context-factory* id)
                              (make-http-context (%lookup *contexts* parent)
                                                 id))) nil))
(rontolisp:wasm-export '%on-context-create
                       :as "proxy_on_context_create"
                       :params '(:u32 :u32))

(%define-callback %on-vm-start "proxy_on_vm_start" (context size) (:s32)
                  :bool nil (on-vm-start context size))

(%define-callback %on-configure "proxy_on_configure" (context size) (:s32)
                  :bool nil (on-configure context size))

(%define-callback %on-tick "proxy_on_tick" (context) ()
                  :void nil (on-tick context))

(%define-callback %on-request-headers "proxy_on_request_headers"
                  (context count eos) (:s32 :bool)
                  :s32 0 (%action (on-request-headers context count eos)))

(%define-callback %on-request-body "proxy_on_request_body" (context size eos)
                  (:s32 :bool)
                  :s32 0 (%action (on-request-body context size eos)))

(%define-callback %on-request-trailers "proxy_on_request_trailers"
                  (context count) (:s32)
                  :s32 0 (%action (on-request-trailers context count)))

(%define-callback %on-response-headers "proxy_on_response_headers"
                  (context count eos) (:s32 :bool)
                  :s32 0 (%action (on-response-headers context count eos)))

(%define-callback %on-response-body "proxy_on_response_body" (context size eos)
                  (:s32 :bool)
                  :s32 0 (%action (on-response-body context size eos)))

(%define-callback %on-response-trailers "proxy_on_response_trailers"
                  (context count) (:s32)
                  :s32 0 (%action (on-response-trailers context count)))

;; CONTEXT here is the plugin's. Host calls made during the reply must reach the
;; stream that made the call, which is what the effective context says. A stream
;; deleted while its call was out (the client went away) just drops the reply.
(%define-callback %on-http-call-response "proxy_on_http_call_response"
                  (context token num-headers body-size num-trailers)
                  (:u32 :s32 :s32 :s32)
                  :void nil
                  (let ((caller (%lookup *callouts* token)))
                    (%forget *callouts* token)
                    (when (and caller (%lookup *contexts* (context-id caller)))
                      (%check "proxy_set_effective_context"
                       (%proxy-set-effective-context (context-id caller)))
                      (setq *current-context* caller)
                      (on-http-call-response caller token num-headers body-size
                                             num-trailers))))

(%define-callback %on-done "proxy_on_done" (context) ()
                  :bool t (on-done context))

(%define-callback %on-log "proxy_on_log" (context) ()
                  :void nil (on-log context))

(defun %on-delete (id)
  (%with-callback (nil)
                  (let ((context (%lookup *contexts* id)))
                    (when context
                      (on-delete context)
                      (%forget *contexts* id))) nil))
(rontolisp:wasm-export '%on-delete :as "proxy_on_delete" :params '(:u32))

(provide :proxy-wasm)
