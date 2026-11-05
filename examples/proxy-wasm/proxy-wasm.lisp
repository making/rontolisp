;;;; proxy-wasm.lisp -- a Proxy-Wasm SDK written from scratch, in Lisp.
;;;;
;;;; Proxy-Wasm (https://github.com/proxy-wasm/spec) is the ABI an Envoy filter
;;;; speaks to its proxy. A filter is a core WebAssembly module: it EXPORTS the
;;;; proxy_on_* callbacks the proxy calls, and it IMPORTS the proxy_* functions it
;;;; calls back. This file is everything between that ABI (v0.2.1) and a filter, which
;;;; is one form:
;;;;
;;;;   (proxy-wasm:define-filter
;;;;     (:on-request-headers (stream num-headers end-of-stream)
;;;;       (proxy-wasm:set-header :request "x-from-lisp" "hello")
;;;;       :continue))
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
  (:export ;; the filter
           :define-filter
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
           :with-buffer :buffer-size
           :set-buffer :set-buffer-bytes
           :continue-request :continue-response
           :send-local-response :http-call
           :shared-data :set-shared-data
           :define-metric :increment-metric
           :record-metric :metric-value
           :get-property :get-property-octets :set-property
           ;; memory
           :alloc :load8
           :store8 :search-bytes))

(in-package :proxy-wasm)

;;; ---------------------------------------------------------------------------
;;; The module's own linear memory
;;; ---------------------------------------------------------------------------

(rontolisp:wasm-import 'alloc
                       :from "rontolisp:memory"
                       :as "alloc"
                       :params '(:u32)
                       :returns :u32)
(rontolisp:wasm-import '%mark :from "rontolisp:memory" :as "mark" :returns :u32)
(rontolisp:wasm-import '%reset
                       :from "rontolisp:memory"
                       :as "reset"
                       :params '(:u32))
(rontolisp:wasm-import 'load8
                       :from "rontolisp:memory"
                       :as "load8"
                       :params '(:u32)
                       :returns :u32)
(rontolisp:wasm-import 'store8
                       :from "rontolisp:memory"
                       :as "store8"
                       :params '(:u32 :u32))
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
(rontolisp:wasm-import '%search
                       :from "rontolisp:memory"
                       :as "search"
                       :params '(:string :u32 :u32)
                       :returns :s32)

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
(rontolisp:wasm-import '%proxy-set-buffer-from
                       :from "env"
                       :as "proxy_set_buffer_bytes"
                       :params '(:s32 :u32 :u32 :u32 :u32)
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

;; A wasm trap: no handler sees it, and it takes the whole VM down. The address is past
;; the end of any linear memory that is not the full 4GiB.
(defun panic () (%load32 #xFFFFFFFC))

;; A host call that only answers OK or fails. Anything else is a defect in the call:
;; %FAILED, which define-filter writes for its (:on-failure ...), says so. A macro, so that
;; a call that succeeds builds no name.
(defmacro %check (operation call)
  (let ((status (gensym "STATUS")))
    `(let ((,status ,call))
       (unless (eql ,status 0) (%failed ,operation ,status))
       nil)))

;; For a call that reads: T for OK, NIL for "there is no such thing here".
(defmacro %found-p (operation call)
  (let ((status (gensym "STATUS")))
    `(let ((,status ,call))
       (cond ((eql ,status 0) t)
             ((or (eql ,status 1) (eql ,status 7)) nil)
             (t
              (%failed ,operation ,status)
              nil)))))

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

;; What takes a keyword from one of the enumerations above, as FUNCTION, a function or a
;; macro, takes its number. A call spelling the keyword has the number at compile time, and
;; a wrong one fails the compile. Any other call reaches the function NAME, as #'NAME does.
(defmacro %define-enumerated (name lambda-list enumeration function)
  (let ((designator (car lambda-list)) (others (cdr lambda-list)))
    `(progn
       (defun ,name ,lambda-list
         (,function (,enumeration ,designator) ,@others))
       (define-compiler-macro ,name (&whole form ,designator ,@others)
         (if (keywordp ,designator)
             (list ',function (,enumeration ,designator) ,@others)
             form)))))

;; What an enumerated macro's own argument is, for the macros below.
(defun %enumerated-form (form enumeration)
  (if (keywordp form) (funcall enumeration form) (list enumeration form)))

;;; ---------------------------------------------------------------------------
;;; Out-pointers
;;; ---------------------------------------------------------------------------
;;; Nothing here frees the bytes the host answered: the whole bump heap goes back to where
;;; it stood when the host entered the module, at the end of that callback. So a filter's
;;; memory stays flat no matter how many headers it reads, and every value it keeps is a
;;; Lisp string the engine owns. The cells the host writes those bytes' addresses to are
;;; three for good: each call reads them before the next call writes them.

(defvar *cell* (alloc 12))

(defvar *cell-1* (+ *cell* 4))

(defvar *cell-2* (+ *cell* 8))

(defun %cell-string () (%memory-string (%load32 *cell*) (%load32 *cell-1*)))

(defun %cell-octets () (%memory-octets (%load32 *cell*) (%load32 *cell-1*)))

;; The top of the bump heap, for a filter that wants to watch that it stays flat.
(defun heap-top () (%mark))

;;; ---------------------------------------------------------------------------
;;; Logging, the clock, ticks
;;; ---------------------------------------------------------------------------

;; A control string that is a literal is compiled at the call site, which leaves the run-time
;; FORMAT interpreter out of the module. A computed one brings it in.
(defmacro %define-log (name level)
  `(defmacro ,name (control &rest args)
     `(progn
        (%proxy-log ,,level (format nil ,control ,@args))
        nil)))

(%define-log log-trace 0)
(%define-log log-debug 1)
(%define-log log-info 2)
(%define-log log-warn 3)
(%define-log log-error 4)
(%define-log log-critical 5)

(defun current-time-nanos ()
  (%check "proxy_get_current_time_nanoseconds"
          (%proxy-get-current-time-nanoseconds *cell*))
  (%load64 *cell*))

(defun set-tick-period (milliseconds)
  (%check "proxy_set_tick_period_milliseconds"
          (%proxy-set-tick-period-milliseconds milliseconds)))

;;; ---------------------------------------------------------------------------
;;; Header maps
;;; ---------------------------------------------------------------------------
;;; MAP is one of :request :request-trailers :response :response-trailers
;;; :call-response :call-response-trailers. These are macros underneath so that a literal
;;; name or value reaches the host call itself, which copies it once.

(defun %header-answer (status)
  (when (%found-p "proxy_get_header_map_value" status) (%cell-string)))

(defmacro %get-header (map name)
  `(%header-answer (%proxy-get-header-map-value ,map ,name *cell* *cell-1*)))

(%define-enumerated get-header (map name) %map-type %get-header)

(defmacro %set-header (map name value)
  `(%check "proxy_replace_header_map_value"
           (%proxy-replace-header-map-value ,map ,name ,value)))

(%define-enumerated set-header (map name value) %map-type %set-header)

(defmacro %add-header (map name value)
  `(%check "proxy_add_header_map_value"
           (%proxy-add-header-map-value ,map ,name ,value)))

(%define-enumerated add-header (map name value) %map-type %add-header)

(defmacro %remove-header (map name)
  `(%check "proxy_remove_header_map_value"
           (%proxy-remove-header-map-value ,map ,name)))

(%define-enumerated remove-header (map name) %map-type %remove-header)

;; The serialized form the ABI uses for a whole map, little-endian:
;;   u32 count, (u32 name-size, u32 value-size) * count, (name NUL value NUL) * count
(defun %parse-pairs (address size)
  (when (plusp size)
    (let* ((count (%load32 address))
           (sizes (+ address 4))
           (data (+ sizes (* 8 count)))
           (pairs '()))
      (dotimes (i count)
        (let ((name-size (%load32 sizes)) (value-size (%load32 (+ sizes 4))))
          (push (cons (%memory-string data name-size)
                      (%memory-string (+ data name-size 1) value-size)) pairs)
          (setq data (+ data name-size value-size 2) sizes (+ sizes 8))))
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

;; The same at compile time, for a literal alist: a literal string, whose UTF-8 encoding is
;; those bytes while each of its numbers stays below 128.
(defun %literal-pairs-p (pairs)
  (and (consp pairs) (eq (car pairs) 'quote) (< (length (cadr pairs)) 128)
       (every (lambda (pair)
                (and (stringp (car pair)) (stringp (cdr pair))
                     (every (lambda (c) (< (char-code c) 128)) (car pair))
                     (every (lambda (c) (< (char-code c) 128)) (cdr pair))
                     (< (length (car pair)) 128) (< (length (cdr pair)) 128)))
              (cadr pairs))))

(defun %u32-text (value)
  (coerce (list (code-char value) (code-char 0) (code-char 0) (code-char 0))
          'string))

(defun %literal-pairs (pairs)
  (let ((nul (string (code-char 0))) (out (%u32-text (length pairs))))
    (dolist (pair pairs)
      (setq out
            (concatenate 'string out (%u32-text (length (car pair)))
                         (%u32-text (length (cdr pair))))))
    (dolist (pair pairs out)
      (setq out (concatenate 'string out (car pair) nul (cdr pair) nul)))))

;; What a macro below passes for a header-map argument: no pairs, a literal alist, or a form
;; answering an alist.
(defun %pairs-form (pairs)
  (cond ((null pairs) (%literal-pairs '()))
        ((%literal-pairs-p pairs) (%literal-pairs (cadr pairs)))
        (t `(%serialize-pairs ,pairs))))

(defun %get-headers (map)
  (when (%found-p "proxy_get_header_map_pairs"
                  (%proxy-get-header-map-pairs map *cell* *cell-1*))
    (%parse-pairs (%load32 *cell*) (%load32 *cell-1*))))

(%define-enumerated get-headers (map) %map-type %get-headers)

(defun %set-headers (map pairs)
  (%check "proxy_set_header_map_pairs"
          (%proxy-set-header-map-pairs map (%serialize-pairs pairs))))

(%define-enumerated set-headers (map pairs) %map-type %set-headers)

;;; ---------------------------------------------------------------------------
;;; Body and configuration buffers
;;; ---------------------------------------------------------------------------
;;; BUFFER is one of :request-body :response-body :call-response-body
;;; :vm-configuration :plugin-configuration.

(defun %buffer-size (buffer)
  (if (%found-p "proxy_get_buffer_status"
                (%proxy-get-buffer-status buffer *cell* *cell-1*))
      (%load32 *cell*)
      0))

(%define-enumerated buffer-size (buffer) %buffer-type %buffer-size)

;; Whether the cells hold LENGTH of the buffer's bytes from START.
(defun %read-buffer (buffer start length)
  (%found-p "proxy_get_buffer_bytes"
            (%proxy-get-buffer-bytes buffer start length *cell* *cell-1*)))

;; Binds ADDRESS and SIZE to LENGTH of the buffer's bytes from START, and runs BODY if the
;; host has any. The bytes are this module's until the callback returns, may be changed in
;; place, and are never copied to the engine's heap: search them with search-bytes, read
;; them with load8.
(defmacro with-buffer ((address size buffer start length) &body body)
  `(when (%read-buffer ,(%enumerated-form buffer '%buffer-type) ,start ,length)
     (let ((,address (%load32 *cell*)) (,size (%load32 *cell-1*))) ,@body)))

;; Whether the string NEEDLE is in the SIZE bytes at ADDRESS: about 3.5 instructions per
;; byte, with no copy of them.
(defun search-bytes (needle address size)
  (not (eql (%search needle address size) -1)))

(defun %whole-buffer-read (buffer)
  (let ((size (%buffer-size buffer)))
    (and (plusp size) (%read-buffer buffer 0 size))))

(defun %get-buffer (buffer) (when (%whole-buffer-read buffer) (%cell-string)))

(%define-enumerated get-buffer (buffer) %buffer-type %get-buffer)

(defun %get-buffer-octets (buffer)
  (when (%whole-buffer-read buffer) (%cell-octets)))

(%define-enumerated get-buffer-octets (buffer) %buffer-type %get-buffer-octets)

;; Replaces SIZE bytes from START, by default the whole buffer. A START past the end
;; appends. A macro, so that a value it can see reaches the host call itself.
(defmacro set-buffer (buffer value &key (start 0) size)
  (let ((type (gensym "TYPE")))
    `(let ((,type ,(%enumerated-form buffer '%buffer-type)))
       (%check "proxy_set_buffer_bytes"
               (%proxy-set-buffer-bytes ,type ,start
                                        ,(if size
                                             `(or ,size (%buffer-size ,type))
                                             `(%buffer-size ,type)) ,value)))))

;; The same, by the SIZE bytes at ADDRESS.
(defun %set-buffer-bytes (buffer start length address size)
  (%check "proxy_set_buffer_bytes"
          (%proxy-set-buffer-from buffer start length address size)))

(%define-enumerated set-buffer-bytes (buffer start length address size)
                    %buffer-type %set-buffer-bytes)

;;; ---------------------------------------------------------------------------
;;; The stream: resuming it, answering it, calling out
;;; ---------------------------------------------------------------------------

(defun continue-request ()
  (%check "proxy_continue_stream" (%proxy-continue-stream 0)))

(defun continue-response ()
  (%check "proxy_continue_stream" (%proxy-continue-stream 1)))

;; Answer the stream from here. The callback making this call returns :pause.
(defmacro send-local-response
    (status &key (details "") (body "") headers (grpc-status -1))
  `(%check "proxy_send_local_response"
           (%proxy-send-local-response ,status ,details ,body
                                       ,(%pairs-form headers) ,grpc-status)))

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
      (unless (eql (car entry) key) (push entry kept)))
    (setf (aref table index) kept)))

(defun %http-call (cluster headers body trailers timeout)
  (when (eql (%proxy-http-call cluster headers body trailers timeout *cell*) 0)
    (let ((token (%load32 *cell*)))
      (%note-call token)
      token)))

;; HEADERS must carry :method, :path and :authority. Answers the call's token, or NIL when
;; the host refuses the call (an unknown cluster, say), which a filter can act on under
;; either error mode. The reply arrives at :on-http-call-response on the context that made
;; the call.
(defmacro http-call (cluster headers &key (body "") trailers (timeout 5000))
  `(%http-call ,cluster ,(%pairs-form headers) ,body ,(%pairs-form trailers)
               ,timeout))

;;; ---------------------------------------------------------------------------
;;; Shared data, metrics, properties
;;; ---------------------------------------------------------------------------

;; Answers the value and its compare-and-swap version, or NIL when the key is absent.
(defun shared-data (key)
  (if (%found-p "proxy_get_shared_data"
                (%proxy-get-shared-data key *cell* *cell-1* *cell-2*))
      (values (%cell-string) (%load32 *cell-2*))
      (values nil 0)))

;; Answers T, or NIL when CAS (when nonzero) names a version that has moved on.
(defun set-shared-data (key value &optional (cas 0))
  (let ((status (%proxy-set-shared-data key value cas)))
    (unless (eql status 8)
      (%check "proxy_set_shared_data" status)
      t)))

;; TYPE is :counter, :gauge or :histogram. Answers the id the other three take.
(defun %define-metric (type name)
  (%check "proxy_define_metric" (%proxy-define-metric type name *cell*))
  (%load32 *cell*))

(%define-enumerated define-metric (type name) %metric-type %define-metric)

(defun increment-metric (id &optional (delta 1))
  (%check "proxy_increment_metric" (%proxy-increment-metric id delta)))

(defun record-metric (id value)
  (%check "proxy_record_metric" (%proxy-record-metric id value)))

(defun metric-value (id)
  (%check "proxy_get_metric" (%proxy-get-metric id *cell*))
  (%load64 *cell*))

;; A property path is its segments joined by NUL: (get-property "request" "path").
(defun %property-path (segments)
  (let ((path (car segments)))
    (dolist (segment (cdr segments))
      (setq path (concatenate 'string path (string (code-char 0)) segment)))
    path))

;; Whether the cells hold the property's bytes: NIL when the host has no such property.
(defun %read-property (segments)
  (%found-p "proxy_get_property"
            (%proxy-get-property (%property-path segments) *cell* *cell-1*)))

(defun get-property (&rest segments)
  (when (%read-property segments) (%cell-string)))

;; For a numeric property, which is its raw little-endian bytes.
(defun get-property-octets (&rest segments)
  (when (%read-property segments) (%cell-octets)))

(defun set-property (value &rest segments)
  (%check "proxy_set_property"
          (%proxy-set-property (%property-path segments) value)))

;;; ---------------------------------------------------------------------------
;;; Contexts
;;; ---------------------------------------------------------------------------
;;; A filter that asks for an object per context has them here, and a filter that does not
;;; has none of this. A callback mostly takes the context the one before it took, which the
;;; last pair answers.

(defvar *contexts* (%table))

(defvar *last-context-id* nil)

(defvar *last-context* nil)

(defun %context (id)
  (cond ((eql id *last-context-id*) *last-context*)
        (t
         (setq *last-context-id* id *last-context* (%lookup *contexts* id))
         *last-context*)))

(defun %add-context (id context)
  (%store *contexts* id context)
  (setq *last-context-id* id *last-context* context))

(defun %delete-context (id)
  (%forget *contexts* id)
  (setq *last-context-id* nil *last-context* nil))

;; Which context made each outgoing call: the ABI hands a call's reply to the PLUGIN's
;; context whichever stream made it, so getting it back to that stream is this SDK's job.
(defvar *calls* (%table))

(defvar *running-context* nil)

;;; ---------------------------------------------------------------------------
;;; The proxy_on_* exports
;;; ---------------------------------------------------------------------------

;; Each callback: its clause, its export, the function behind it, the types after the
;; context's id, what a body answers, and what the export answers when the filter leaves it
;; out. Those marked :optional no host asks for, so leaving them out leaves out the export
;; too: Sluice refuses a module missing any of the others.
(defun %callbacks ()
  '((:on-vm-start "proxy_on_vm_start" %on-vm-start (:s32) :boolean t)
    (:on-configure "proxy_on_configure" %on-configure (:s32) :boolean t)
    (:on-tick "proxy_on_tick" %on-tick () :void :optional)
    (:on-request-headers "proxy_on_request_headers" %on-request-headers
                         (:s32 :bool)
                         :action nil)
    (:on-request-body "proxy_on_request_body" %on-request-body (:s32 :bool)
                      :action nil)
    (:on-request-trailers "proxy_on_request_trailers" %on-request-trailers
                          (:s32)
                          :action :optional)
    (:on-response-headers "proxy_on_response_headers" %on-response-headers
                          (:s32 :bool)
                          :action nil)
    (:on-response-body "proxy_on_response_body" %on-response-body (:s32 :bool)
                       :action nil)
    (:on-response-trailers "proxy_on_response_trailers" %on-response-trailers
                           (:s32)
                           :action :optional)
    (:on-done "proxy_on_done" %on-done () :boolean t)
    (:on-log "proxy_on_log" %on-log () :void nil)))

;; What every callback runs inside. A Lisp error must not become a wasm trap, which would
;; take the whole VM down: it is logged and the callback answers FALLBACK. The log says
;; only that it happened, since the report is the expensive part: a filter that wants it
;; catches the condition itself. Under (:on-failure :trap) there is no handler, and the
;; error does trap. On the way out the bump heap pops back to where it stood, releasing
;; every byte the host answered during the call. A macro, whose template no pass reads:
;; a function naming HANDLER-CASE would count as one in a filter that traps.
(defmacro %guarded (trap fallback form)
  (let ((mark (gensym "MARK")) (result (gensym "RESULT")))
    `(let* ((,mark (%mark))
            (,result
             ,(if trap
                  form
                  `(handler-case ,form
                     (error ()
                       (%proxy-log 4 "proxy-wasm: a callback signaled an error")
                       ,fallback)))))
       (%reset ,mark)
       ,result)))

;; BODY with PARAMETER bound to the context of ID: its object where the filter has them,
;; and then a callback for a context the module does not hold answers FALLBACK.
(defun %with-context (parameter id tracked calls fallback body)
  (let ((inner
         `(let ((,parameter ,(if tracked `(%context ,id) id)))
            (declare (ignorable ,parameter))
            ,@(when calls `((setq *running-context* ,id)))
            ,body)))
    (if tracked `(if (%context ,id) ,inner ,fallback) inner)))

(defun %argument-names (types)
  (mapcar (lambda (type) (gensym "ARGUMENT")) types))

(defun %callback-forms (callback clause tracked calls trap)
  (let* ((key (car callback))
         (export (cadr callback))
         (name (caddr callback))
         (types (cadddr callback))
         (returns (car (cddddr callback)))
         (default (cadr (cddddr callback)))
         (id (gensym "ID"))
         (arguments (%argument-names types))
         (result (if (eq returns :void) '() '(:returns :bool))))
    (cond ((and (null clause) (eq default :optional)) '())
          (t
           (list (if clause
                     (let ((parameters (cadr clause)))
                       `(defun ,name (,id ,@arguments)
                          (%guarded ,trap nil
                                    ,(%with-context (car parameters) id tracked
                                                    calls nil
                                                    `(let ,(mapcar
                                                            #'list
                                                            (cdr parameters)
                                                            arguments)
                                                       (declare
                                                        (ignorable
                                                         ,@(cdr parameters)))
                                                       ,(cond
                                                         ((eq returns :action)
                                                          `(eq (progn
                                                                 ,@(cddr
                                                                    clause))
                                                               :pause))
                                                         ((eq returns :void)
                                                          `(progn
                                                             ,@(cddr clause)
                                                             nil))
                                                         (t
                                                          `(progn
                                                             ,@(cddr
                                                                clause)))))))))
                     `(defun ,name (,id ,@arguments)
                        (declare (ignore ,id ,@arguments))
                        ,default))
                 `(rontolisp:wasm-export ',name
                                         :as ,export
                                         :params '(:u32 ,@types) ,@result))))))

;; A parent of 0 makes a root context. Every other context is a stream of its parent.
(defun %context-create-forms (clauses tracked trap)
  (let* ((id (gensym "ID"))
         (parent (gensym "PARENT"))
         (root (assoc :root clauses))
         (stream (assoc :stream clauses))
         (given (assoc :on-context-create clauses))
         (object
          `(if (eql ,parent 0)
               ,(if root
                    `(let ((,(car (cadr root)) ,id))
                       (declare (ignorable ,(car (cadr root))))
                       ,@(cddr root))
                    id)
               ,(if stream
                    `(let ((,(car (cadr stream)) ,id)
                           (,(cadr (cadr stream)) (%context ,parent)))
                       (declare (ignorable ,@(cadr stream)))
                       ,@(cddr stream))
                    id)))
         (body
          `(progn
             ,@(when tracked `((%add-context ,id ,object)))
             ,@(when given
                 `((let ((,(car (cadr given)) ,id)
                         (,(cadr (cadr given)) ,parent))
                     (declare (ignorable ,@(cadr given)))
                     ,@(cddr given))))
             nil)))
    (list `(defun %on-context-create (,id ,parent)
             (declare (ignorable ,id ,parent))
             ,@(when (or tracked given) `((%guarded ,trap nil ,body))))
          `(rontolisp:wasm-export '%on-context-create
                                  :as "proxy_on_context_create"
                                  :params '(:u32 :u32)))))

(defun %delete-forms (clauses tracked calls trap)
  (let* ((id (gensym "ID"))
         (given (assoc :on-delete clauses))
         (body
          `(progn
             ,@(when given
                 (list
                  (%with-context (car (cadr given)) id tracked calls nil
                                 `(progn
                                    ,@(cddr given)
                                    nil))))
             ,@(when tracked `((%delete-context ,id)))
             nil)))
    (list `(defun %on-delete (,id)
             (declare (ignorable ,id))
             ,@(when (or tracked given) `((%guarded ,trap nil ,body))))
          `(rontolisp:wasm-export '%on-delete
                                  :as "proxy_on_delete"
                                  :params '(:u32)))))

;; The first argument here is the plugin's context. Host calls made during the reply must
;; reach the stream that made the call, which is what the effective context says. A stream
;; deleted while its call was out (the client went away) just drops the reply.
(defun %call-response-forms (clause trap)
  (when clause
    (let* ((id (gensym "ID"))
           (token (gensym "TOKEN"))
           (caller (gensym "CALLER"))
           (parameters (cadr clause))
           (arguments (%argument-names '(:s32 :s32 :s32))))
      (list `(defun %on-http-call-response (,id ,token ,@arguments)
               (declare (ignorable ,id))
               (%guarded ,trap nil
                         (let ((,caller (%lookup *calls* ,token)))
                           (%forget *calls* ,token)
                           (when (and ,caller (%context ,caller))
                             (%check "proxy_set_effective_context"
                                     (%proxy-set-effective-context ,caller))
                             (setq *running-context* ,caller)
                             (let ((,(car parameters) (%context ,caller))
                                   (,(cadr parameters) ,token)
                                   ,@(mapcar
                                      #'list
                                      (cddr parameters)
                                      arguments))
                               (declare (ignorable ,@parameters))
                               ,@(cddr clause)))
                           nil)))
            `(rontolisp:wasm-export '%on-http-call-response
                                    :as "proxy_on_http_call_response"
                                    :params '(:u32 :u32 :s32 :s32 :s32))))))

;; A filter. Each clause but three is a callback the host calls:
;;
;;   (:on-request-headers (stream num-headers end-of-stream) body...)
;;
;; The first parameter is the context, and a body answers :continue or :pause. Those of
;; on-vm-start, on-configure and on-done answer a boolean, and the others what they like.
;; A callback left out costs what its export takes to answer.
;;
;;   :on-vm-start, :on-configure (root size)     :on-tick (root)
;;   :on-request-headers, :on-response-headers (stream num-headers end-of-stream)
;;   :on-request-body, :on-response-body (stream body-size end-of-stream)
;;   :on-request-trailers, :on-response-trailers (stream num-trailers)
;;   :on-http-call-response (context token num-headers body-size num-trailers)
;;   :on-context-create (id parent)              on the ids, whatever a context is
;;   :on-done, :on-log, :on-delete (context)
;;
;; A context is its id, an integer, and the module holds nothing per context. The clauses
;; that say otherwise make it what they answer, an object of any kind but NIL:
;;
;;   (:root (id) body...)         a root context
;;   (:stream (id root) body...)  a stream context, ROOT the context of its parent
;;
;; The third says what a failed host call and a signaled error do:
;;
;;   (:on-failure :log | :trap)   logged and the callback answers its default, the default;
;;                                or a trap, with no handler in any callback
;;
(defmacro define-filter (&rest clauses)
  (dolist (clause clauses)
    (unless (or (member (car clause)
                        '(:root :stream :on-failure
                                :on-context-create :on-delete
                                :on-http-call-response))
                (assoc (car clause) (%callbacks)))
      (error "proxy-wasm: no clause ~s in define-filter" (car clause))))
  (let* ((trap (eq (cadr (assoc :on-failure clauses)) :trap))
         (calls (assoc :on-http-call-response clauses))
         (tracked (or (assoc :root clauses) (assoc :stream clauses) calls))
         (forms '()))
    (dolist (callback (%callbacks))
      (dolist (form
               (%callback-forms callback (assoc (car callback) clauses) tracked
                                calls trap))
        (push form forms)))
    `(progn
       (defun %abi-version () nil)
       (rontolisp:wasm-export '%abi-version :as "proxy_abi_version_0_2_1")
       (defun %on-memory-allocate (size) (alloc size))
       (rontolisp:wasm-export '%on-memory-allocate
                              :as "proxy_on_memory_allocate"
                              :params '(:u32)
                              :returns :u32)
       (defun %failed (operation status)
         (%proxy-log 4
          (format nil "proxy-wasm: ~a answered status ~a" operation status))
         ,(if trap '(panic) '(error "host call failed")))
       (defun %note-call (token)
         ,@(when calls `((%store *calls* token *running-context*)))
         nil)
       ,@(reverse forms)
       ,@(%context-create-forms clauses tracked trap)
       ,@(%delete-forms clauses tracked calls trap)
       ,@(%call-response-forms calls trap))))

(provide :proxy-wasm)
