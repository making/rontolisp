;;;; proxy-wasm.lisp -- a Proxy-Wasm SDK for --no-gc, in macros.
;;;;
;;;; The same ABI (v0.2.1) as ../proxy-wasm.lisp, for a filter that gives up CLOS and
;;;; conditions to weigh a few KB instead of a hundred, and to run on a host without
;;;; wasm-GC. A filter is one form:
;;;;
;;;;   (proxy-wasm:define-filter
;;;;     (:on-response-headers (stream num-headers end-of-stream)
;;;;       (proxy-wasm:add-header :response "x-from-lisp" "hello")
;;;;       :continue))
;;;;
;;;; What it costs compared with the other SDK, in README.md. Nothing here reaches a
;;;; filter that does not call it: what no export reaches is not compiled.
;;;;
;;;; A STRING is the backend's: [len][bytes] in linear memory, read with `length`, `char`
;;;; and `string=`, built with `format nil` and `concatenate`. Everything a callback
;;;; allocates is popped when it returns, so a string lasts until then. What has to last
;;;; longer goes into a :state field. A BODY is too big to copy and to index by
;;;; character, so it is an address pair, read with `load8` and `search-bytes`.

(defpackage :proxy-wasm
  (:use :cl)
  (:export ;; the filter
           :define-filter :context-root
           ;; host functions
           :log-trace :log-debug
           :log-info :log-warn
           :log-error :log-critical
           :panic :map-type
           :buffer-type :get-header
           :set-header :add-header
           :remove-header :do-headers
           :set-headers :make-pairs
           :add-pair :finish-pairs
           :get-buffer :with-buffer
           :set-buffer :set-buffer-bytes
           :send-local-response :http-call
           :define-metric :increment-metric
           :get-property :get-property-integer
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
(rontolisp:wasm-import '%store32
                       :from "rontolisp:memory"
                       :as "store32"
                       :params '(:u32 :u32))
(rontolisp:wasm-import '%load64
                       :from "rontolisp:memory"
                       :as "load64"
                       :params '(:u32)
                       :returns :s64)
(rontolisp:wasm-import '%store64
                       :from "rontolisp:memory"
                       :as "store64"
                       :params '(:u32 :s64))
(rontolisp:wasm-import '%string
                       :from "rontolisp:memory"
                       :as "string"
                       :params '(:u32 :u32)
                       :returns :string)
(rontolisp:wasm-import '%address
                       :from "rontolisp:memory"
                       :as "address"
                       :params '(:string)
                       :returns :u32)
(rontolisp:wasm-import '%size
                       :from "rontolisp:memory"
                       :as "size"
                       :params '(:string)
                       :returns :u32)

;;; ---------------------------------------------------------------------------
;;; The proxy_* host functions: only a reached one becomes an import
;;; ---------------------------------------------------------------------------

(rontolisp:wasm-import '%proxy-log
                       :from "env"
                       :as "proxy_log"
                       :params '(:s32 :string)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-get-buffer-bytes
                       :from "env"
                       :as "proxy_get_buffer_bytes"
                       :params '(:s32 :u32 :u32 :u32 :u32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-set-buffer-string
                       :from "env"
                       :as "proxy_set_buffer_bytes"
                       :params '(:s32 :u32 :u32 :string)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-set-buffer-bytes
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
                       :params '(:s32 :string)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-get-header-map-value
                       :from "env"
                       :as "proxy_get_header_map_value"
                       :params '(:s32 :string :u32 :u32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-add-header-map-value
                       :from "env"
                       :as "proxy_add_header_map_value"
                       :params '(:s32 :string :string)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-replace-header-map-value
                       :from "env"
                       :as "proxy_replace_header_map_value"
                       :params '(:s32 :string :string)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-remove-header-map-value
                       :from "env"
                       :as "proxy_remove_header_map_value"
                       :params '(:s32 :string)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-send-local-response
                       :from "env"
                       :as "proxy_send_local_response"
                       :params '(:s32 :string :string :string :s32)
                       :returns :s32)
(rontolisp:wasm-import '%proxy-http-call
                       :from "env"
                       :as "proxy_http_call"
                       :params '(:string :string :string :string :u32 :u32)
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
(rontolisp:wasm-import '%proxy-get-property
                       :from "env"
                       :as "proxy_get_property"
                       :params '(:string :u32 :u32)
                       :returns :s32)

;;; ---------------------------------------------------------------------------
;;; At compile time: the ABI's enumerations, and what a form spells
;;; ---------------------------------------------------------------------------
;;; A keyword is not a value on this backend, so each one is a number by the time the
;;; compiler sees it. A form that is not a keyword is a number at run time.

(defun %enumeration (designator table what)
  (if (keywordp designator)
      (let ((entry (assoc designator table)))
        (unless entry (error "proxy-wasm: ~s is not a ~a" designator what))
        (cdr entry))
      designator))

(defun %map-number (map)
  (%enumeration map
                '((:request . 0) (:request-trailers . 1) (:response . 2)
                  (:response-trailers . 3) (:call-response . 6)
                  (:call-response-trailers . 7)) "header map"))

(defun %buffer-number (buffer)
  (%enumeration buffer
                '((:request-body . 0) (:response-body . 1)
                  (:call-response-body . 4) (:vm-configuration . 6)
                  (:plugin-configuration . 7)) "buffer"))

(defun %metric-number (type)
  (%enumeration type '((:counter . 0) (:gauge . 1) (:histogram . 2))
                "metric type"))

;; The ABI's serialized header map of a literal alist, as a literal string:
;;   u32 count, (u32 name-size, u32 value-size) * count, (name NUL value NUL) * count
(defun %u32-text (value)
  (when (> value 127)
    (error "proxy-wasm: ~a bytes in a literal header: build it with add-pair"
           value))
  (coerce (list (code-char value) (code-char 0) (code-char 0) (code-char 0))
          'string))

(defun %serialized-pairs (pairs)
  (let ((nul (string (code-char 0))) (out (%u32-text (length pairs))))
    (dolist (pair pairs)
      (setq out
            (concatenate 'string out (%u32-text (length (car pair)))
                         (%u32-text (length (cdr pair))))))
    (dolist (pair pairs out)
      (setq out (concatenate 'string out (car pair) nul (cdr pair) nul)))))

;; What a header-map argument is: a literal alist, or a form answering a serialized map.
(defun %pairs-form (pairs)
  (cond ((null pairs) (%serialized-pairs '()))
        ((and (consp pairs) (eq (car pairs) 'quote))
         (%serialized-pairs (cadr pairs)))
        (t pairs)))

;; A property path is its segments joined by NUL.
(defun %property-path (segments)
  (let ((path (car segments)))
    (dolist (segment (cdr segments) path)
      (setq path (concatenate 'string path (string (code-char 0)) segment)))))

;;; ---------------------------------------------------------------------------
;;; Host calls, memory and strings
;;; ---------------------------------------------------------------------------
;;; A call that fails answers NIL and the callback goes on, or traps under
;;; (:on-failure :trap): this backend has no conditions. %CHECK is what define-filter
;;; writes for that choice. A trap takes the whole VM down.

;; The address is past the end of any linear memory that is not the full 4GiB.
(defun panic () (%load32 4294967292))

;; For a call that reads: T for OK, NIL for "there is no such thing here".
(defun %found-p (status)
  (if (or (= status 1) (= status 7)) nil (%check status)))

(defun %cell-string (cells) (%string (%load32 cells) (%load32 (+ cells 4))))

(defun %bytes= (a b size)
  (let ((i 0))
    (loop while (and (< i size) (= (load8 (+ a i)) (load8 (+ b i))))
          do (setq i (+ i 1)))
    (= i size)))

;; Whether the string NEEDLE is in the SIZE bytes at ADDRESS. One pass over the bytes, so
;; a body costs what its size says: `char` would walk from the start at every index.
(defun search-bytes (needle address size)
  (let ((start (%address needle))
        (needle-size (%size needle))
        (i 0)
        (found nil))
    (loop while (and (not found) (<= (+ i needle-size) size))
          do (setq found (%bytes= start (+ address i) needle-size))
            (setq i (+ i 1)))
    found))

(defun %copy-bytes (to from size)
  (dotimes (i size) (store8 (+ to i) (load8 (+ from i)))))

;;; ---------------------------------------------------------------------------
;;; Logging
;;; ---------------------------------------------------------------------------

(defmacro log-trace (control &rest args)
  `(%proxy-log 0 (format nil ,control ,@args)))
(defmacro log-debug (control &rest args)
  `(%proxy-log 1 (format nil ,control ,@args)))
(defmacro log-info (control &rest args)
  `(%proxy-log 2 (format nil ,control ,@args)))
(defmacro log-warn (control &rest args)
  `(%proxy-log 3 (format nil ,control ,@args)))
(defmacro log-error (control &rest args)
  `(%proxy-log 4 (format nil ,control ,@args)))
(defmacro log-critical (control &rest args)
  `(%proxy-log 5 (format nil ,control ,@args)))

;;; ---------------------------------------------------------------------------
;;; Header maps
;;; ---------------------------------------------------------------------------
;;; MAP is one of :request :request-trailers :response :response-trailers
;;; :call-response :call-response-trailers, or a number from (map-type :request).

(defmacro map-type (map) (%map-number map))

(defun %get-header (map name)
  (let ((cells (alloc 8)))
    (when (%found-p (%proxy-get-header-map-value map name cells (+ cells 4)))
      (%cell-string cells))))

;; The value, or NIL when there is no such header.
(defmacro get-header (map name) `(%get-header ,(%map-number map) ,name))

(defmacro set-header (map name value)
  `(%check (%proxy-replace-header-map-value ,(%map-number map) ,name ,value)))

(defmacro add-header (map name value)
  `(%check (%proxy-add-header-map-value ,(%map-number map) ,name ,value)))

(defmacro remove-header (map name)
  `(%check (%proxy-remove-header-map-value ,(%map-number map) ,name)))

;; Runs BODY for each field with NAME and VALUE bound to strings.
(defmacro do-headers ((name value map) &body body)
  (let ((cells (gensym "CELLS"))
        (sizes (gensym "SIZES"))
        (data (gensym "DATA"))
        (count (gensym "COUNT"))
        (i (gensym "I")))
    `(let ((,cells (alloc 8)))
       (when (%found-p
              (%proxy-get-header-map-pairs ,(%map-number map) ,cells
                                           (+ ,cells 4)))
         (let* ((,sizes (%load32 ,cells))
                (,count (%load32 ,sizes))
                (,data (+ ,sizes 4 (* 8 ,count))))
           (dotimes (,i ,count)
             (let* ((,name (%string ,data (%load32 (+ ,sizes 4 (* 8 ,i)))))
                    (,value
                     (%string (+ ,data (%size ,name) 1)
                              (%load32 (+ ,sizes 8 (* 8 ,i))))))
               ,@body
               (setq ,data (+ ,data (%size ,name) (%size ,value) 2)))))))))

;; A map built at run time, for at most COUNT fields and BYTES of names and values:
;;   u32 data-size, u32 max-count, then the ABI's serialized form with room for max-count
(defun make-pairs (count bytes)
  (let ((pairs (alloc (+ 12 (* 8 count) bytes (* 2 count)))))
    (%store32 pairs 0)
    (%store32 (+ pairs 4) count)
    (%store32 (+ pairs 8) 0)
    pairs))

(defun %pairs-data (pairs) (+ pairs 12 (* 8 (%load32 (+ pairs 4)))))

(defun add-pair (pairs name value)
  (let* ((count (%load32 (+ pairs 8)))
         (name-size (%size name))
         (value-size (%size value))
         (to (+ (%pairs-data pairs) (%load32 pairs))))
    (when (>= count (%load32 (+ pairs 4))) (panic))
    (%store32 (+ pairs 12 (* 8 count)) name-size)
    (%store32 (+ pairs 16 (* 8 count)) value-size)
    (%copy-bytes to (%address name) name-size)
    (store8 (+ to name-size) 0)
    (%copy-bytes (+ to name-size 1) (%address value) value-size)
    (store8 (+ to name-size 1 value-size) 0)
    (%store32 pairs (+ (%load32 pairs) name-size value-size 2))
    (%store32 (+ pairs 8) (+ count 1))))

;; The serialized form: the sizes of the fields that were added, and the data after them.
(defun finish-pairs (pairs)
  (let* ((count (%load32 (+ pairs 8)))
         (sizes (* 8 count))
         (data-size (%load32 pairs)))
    (%copy-bytes (+ pairs 12 sizes) (%pairs-data pairs) data-size)
    (%string (+ pairs 8) (+ 4 sizes data-size))))

;; PAIRS is a quoted literal alist, spelled out here at compile time, or a form answering
;; (finish-pairs ...).
(defmacro set-headers (map pairs)
  `(%check
    (%proxy-set-header-map-pairs ,(%map-number map) ,(%pairs-form pairs))))

;;; ---------------------------------------------------------------------------
;;; Body and configuration buffers
;;; ---------------------------------------------------------------------------
;;; BUFFER is one of :request-body :response-body :call-response-body
;;; :vm-configuration :plugin-configuration, or a number from (buffer-type :request-body).
;;; The callback that reads one has its size.

(defmacro buffer-type (buffer) (%buffer-number buffer))

(defun %buffer-cells (buffer start size)
  (let ((cells (alloc 8)))
    (when (%found-p
           (%proxy-get-buffer-bytes buffer start size cells (+ cells 4)))
      cells)))

;; Binds ADDRESS and SIZE to the buffer's bytes, and runs BODY if the host has any. The
;; bytes are this module's, and may be changed in place.
(defmacro with-buffer ((address size buffer start length) &body body)
  (let ((cells (gensym "CELLS")))
    `(let ((,cells (%buffer-cells ,(%buffer-number buffer) ,start ,length)))
       (when (> ,cells 0)
         (let ((,address (%load32 ,cells)) (,size (%load32 (+ ,cells 4))))
           ,@body)))))

;; As a string, or NIL.
(defmacro get-buffer (buffer start length)
  (let ((address (gensym "ADDRESS")) (size (gensym "SIZE")))
    `(with-buffer (,address ,size ,buffer ,start ,length)
       (%string ,address ,size))))

;; Replaces LENGTH bytes from START by a string. A START past the end appends.
(defmacro set-buffer (buffer start length value)
  `(%check
    (%proxy-set-buffer-string ,(%buffer-number buffer) ,start ,length ,value)))

;; The same, by the SIZE bytes at ADDRESS.
(defmacro set-buffer-bytes (buffer start length address size)
  `(%check
    (%proxy-set-buffer-bytes ,(%buffer-number buffer) ,start ,length ,address
                             ,size)))

;;; ---------------------------------------------------------------------------
;;; Answering the stream, calling out, metrics, properties
;;; ---------------------------------------------------------------------------

;; Answer the stream from here. The callback making this call answers :pause.
(defmacro send-local-response
    (status &key (details "") (body "") headers (grpc-status -1))
  `(%check
    (%proxy-send-local-response ,status ,details ,body ,(%pairs-form headers)
                                ,grpc-status)))

(defun %http-call (cluster headers body trailers timeout)
  (let ((cell (alloc 4)))
    (when (= (%proxy-http-call cluster headers body trailers timeout cell) 0)
      (%load32 cell))))

;; The call's token, or NIL when it is refused: never a trap, as a filter has to be able
;; to go on without the call. The reply reaches :on-http-call-response on the plugin's
;; context, whichever stream made the call.
(defmacro http-call (cluster headers &key (body "") trailers (timeout 5000))
  `(%http-call ,cluster ,(%pairs-form headers) ,body ,(%pairs-form trailers)
               ,timeout))

(defun %define-metric (type name)
  (let ((cell (alloc 4)))
    (%check (%proxy-define-metric type name cell))
    (%load32 cell)))

;; TYPE is :counter, :gauge or :histogram. Answers the id increment-metric takes.
(defmacro define-metric (type name)
  `(%define-metric ,(%metric-number type) ,name))

(defmacro increment-metric (id delta)
  `(%check (%proxy-increment-metric ,id ,delta)))

(defun %property-cells (path)
  (let ((cells (alloc 8)))
    (when (%found-p (%proxy-get-property path cells (+ cells 4))) cells)))

(defun %get-property (path)
  (let ((cells (%property-cells path)))
    (when (> cells 0) (%cell-string cells))))

;; (get-property "request" "path"): the segments are literal strings. The value, or NIL.
(defmacro get-property (&rest segments)
  `(%get-property ,(%property-path segments)))

;; For a numeric property, which is its 8 raw little-endian bytes. 0 when there is none.
(defun %get-property-integer (path)
  (let ((cells (%property-cells path)))
    (if (> cells 0) (%load64 (%load32 cells)) 0)))

(defmacro get-property-integer (&rest segments)
  `(%get-property-integer ,(%property-path segments)))

;;; ---------------------------------------------------------------------------
;;; State per context
;;; ---------------------------------------------------------------------------
;;; A block of the bump heap per live context, on a list: [next][id][root][fields].
;;; It is reserved in proxy_on_context_create, which pops nothing, and a deleted
;;; context's block (id 0) goes to the next new one, so the heap stays at the most
;;; contexts ever alive. Linear in those, which are few per VM. The head is at address 4:
;;; --no-gc leaves bytes 4 to 7 of its memory to the program.

(defun %context-block (id)
  (let ((block (%load32 4)))
    (loop while (and (/= block 0) (/= (%load32 (+ block 4)) id))
          do (setq block (%load32 block)))
    block))

;; Of a context the module was told about: a host that skipped the creation traps here,
;; rather than the filter writing to the memory near address 0.
(defun %context-fields (id)
  (let ((block (%context-block id)))
    (when (= block 0) (panic))
    (+ block 12)))

(defun %context-new (id root size)
  (let ((block (%context-block 0)))
    (when (= block 0)
      (setq block (alloc (+ size 12)))
      (%store32 block (%load32 4))
      (%store32 4 block))
    (dotimes (i size) (store8 (+ block 12 i) 0))
    (%store32 (+ block 4) id)
    (%store32 (+ block 8) root)))

(defun %context-free (id) (%store32 (- (%context-fields id) 8) 0))

;; The root context a stream was made by, whose fields a stream can read. Its own id, for
;; a root.
(defun context-root (id)
  (let ((root (%load32 (- (%context-fields id) 4)))) (if (= root 0) id root)))

(defun %string-field (id offset)
  (let ((field (+ (%context-fields id) offset)))
    (%string (+ field 4) (%load32 field))))

;; T, or NIL when VALUE does not fit, and the field stays as it was.
(defun %set-string-field (id offset capacity value)
  (let ((field (+ (%context-fields id) offset)) (size (%size value)))
    (when (<= size capacity)
      (%store32 field size)
      (%copy-bytes (+ field 4) (%address value) size)
      t)))

(defun %integer-field (id offset) (%load64 (+ (%context-fields id) offset)))

(defun %set-integer-field (id offset value)
  (%store64 (+ (%context-fields id) offset) value)
  value)

;; (name size) is a string of at most SIZE bytes, and a bare name an integer. Both start
;; out empty or 0, and are readable and setf-able as (name context-id).
(defun %state-accessors (fields)
  (let ((offset 0) (forms '()))
    (dolist (field fields (values (reverse forms) offset))
      (let ((name (if (consp field) (car field) field))
            (id (gensym "ID"))
            (place (gensym "PLACE"))
            (value (gensym "VALUE")))
        (push (if (consp field)
                  `(defun ,name (,id) (%string-field ,id ,offset))
                  `(defun ,name (,id) (%integer-field ,id ,offset))) forms)
        (push (if (consp field)
                  `(defsetf ,name (,place)
                     (,value)
                     (list '%set-string-field ,place ,offset ,(cadr field)
                           ,value))
                  `(defsetf ,name (,place)
                     (,value)
                     (list '%set-integer-field ,place ,offset ,value))) forms)
        (setq offset (+ offset (if (consp field) (+ 4 (cadr field)) 8)))))))

;;; ---------------------------------------------------------------------------
;;; The proxy_on_* exports
;;; ---------------------------------------------------------------------------

;; Each callback: its export, the function behind it, what the ABI gives and takes, and
;; what it answers when the filter leaves it out. Those marked :optional no host asks for,
;; so leaving them out leaves out the export too. Sluice refuses a module missing any of
;; the others.
(defun %callbacks ()
  '((:on-vm-start "proxy_on_vm_start" %on-vm-start (:u32 :s32) :s32 1)
    (:on-configure "proxy_on_configure" %on-configure (:u32 :s32) :s32 1)
    (:on-context-create "proxy_on_context_create" %on-context-create (:u32 :u32)
                        :void)
    (:on-request-headers "proxy_on_request_headers" %on-request-headers
                         (:u32 :s32 :s32)
                         :s32 0)
    (:on-request-body "proxy_on_request_body" %on-request-body (:u32 :s32 :s32)
                      :s32 0)
    (:on-response-headers "proxy_on_response_headers" %on-response-headers
                          (:u32 :s32 :s32)
                          :s32 0)
    (:on-response-body "proxy_on_response_body" %on-response-body
                       (:u32 :s32 :s32)
                       :s32 0) (:on-done "proxy_on_done" %on-done (:u32) :s32 1)
    (:on-log "proxy_on_log" %on-log (:u32) :void)
    (:on-delete "proxy_on_delete" %on-delete (:u32) :void)
    (:on-request-trailers "proxy_on_request_trailers" %on-request-trailers
                          (:u32 :s32)
                          :s32 :optional)
    (:on-response-trailers "proxy_on_response_trailers" %on-response-trailers
                           (:u32 :s32)
                           :s32 :optional)
    (:on-http-call-response "proxy_on_http_call_response" %on-http-call-response
                            (:u32 :u32 :s32 :s32 :s32)
                            :void :optional)))

;; An action is a number by the time the compiler sees it.
(defun %actions (form)
  (cond ((eq form :continue) 0)
        ((eq form :pause) 1)
        ((consp form) (cons (%actions (car form)) (%actions (cdr form))))
        (t form)))

;; The function behind one export. What BODY allocates goes back on the way out. A
;; context's block has to outlast that: BEFORE and AFTER are outside it.
(defun %callback-function (name params returns body before after)
  (let ((mark (gensym "MARK")) (result (gensym "RESULT")))
    `(defun ,name ,params
       ,@before
       ,@(cond ((null body) '())
               ((eq returns :void)
                `((let ((,mark (%mark)))
                    ,@body
                    (%reset ,mark))))
               (t `((let* ((,mark (%mark)) (,result (progn ,@body)))
                      (%reset ,mark)
                      ,result))))
       ,@after)))

(defun %callback-forms (callback clause state-size)
  (let* ((key (car callback))
         (export (cadr callback))
         (name (caddr callback))
         (types (cadddr callback))
         (returns (car (cddddr callback)))
         (default (cadr (cddddr callback)))
         (parameters
          (or (cadr clause) (mapcar (lambda (type) (gensym "ARGUMENT")) types)))
         (keeps-state (> state-size 0)))
    (unless (and (eq default :optional) (null clause))
      (list (%callback-function name parameters returns
                                (if clause (%actions (cddr clause)) '())
                                (when (and keeps-state
                                           (eq key :on-context-create))
                                  `((%context-new ,(car parameters)
                                                  ,(cadr parameters)
                                                  ,state-size)))
                                (cond ((and keeps-state (eq key :on-delete))
                                       `((%context-free ,(car parameters))))
                                      ((and (null clause)
                                            (not (eq returns :void)))
                                       (list default))))
            `(rontolisp:wasm-export ',name
              :as ,export
              :params ',types
              ,@(if (eq returns :void) '() `(:returns ,returns)))))))

;; A filter. Each clause but two is a callback the host calls, by the name the other SDK
;; gives its generic function:
;;
;;   (:on-request-headers (stream num-headers end-of-stream) body...)
;;
;; The first parameter is the context's id, and a body answers :continue or :pause, or a
;; boolean, as that generic function says in ../proxy-wasm.lisp.
;;
;;   (:state field...)            fields every context has: see %state-accessors
;;   (:on-failure :log | :trap)   what a failed host call does before answering NIL
;;
(defmacro define-filter (&rest clauses)
  (dolist (clause clauses)
    (unless (or (member (car clause) '(:state :on-failure))
                (assoc (car clause) (%callbacks)))
      (error "proxy-wasm: no clause ~s in define-filter" (car clause))))
  (let* ((accessors
          (multiple-value-list (%state-accessors (cdr (assoc :state clauses)))))
         (state-size (cadr accessors))
         (forms '()))
    (dolist (callback (%callbacks))
      (dolist (form
               (%callback-forms callback (assoc (car callback) clauses)
                                state-size))
        (push form forms)))
    `(progn
       (defun %abi-version () nil)
       (rontolisp:wasm-export '%abi-version :as "proxy_abi_version_0_2_1")
       (defun %on-memory-allocate (size) (alloc size))
       (rontolisp:wasm-export '%on-memory-allocate
                              :as "proxy_on_memory_allocate"
                              :params '(:u32)
                              :returns :u32)
       (defun %check (status)
         ,@(if (eq (cadr (assoc :on-failure clauses)) :trap)
               '((when (/= status 0) (panic)) t)
               '((when (/= status 0)
                   (log-error "proxy-wasm: a host call answered status ~a"
                              status)) (= status 0))))
       ,@(car accessors)
       ,@(reverse forms))))

(provide :proxy-wasm)
