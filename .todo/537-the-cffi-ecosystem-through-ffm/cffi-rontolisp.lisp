;;;; SPIKE ONLY: the cffi-sys backend for rontolisp, over java.lang.foreign
;;;; reached through the java: interop package (a real implementation would
;;;; bind FFM directly, the objc: shape).

(in-package #:cffi-sys)


;;;# The FFM plumbing

(defvar *linker* (java:static "java.lang.foreign.Linker" "nativeLinker"))
(defvar *default-lookup* (java:call *linker* "defaultLookup"))
(defvar *arena* (java:static "java.lang.foreign.Arena" "global"))
(defvar *libraries* (make-hash-table :test 'equal))
(defvar *handles* (make-hash-table :test 'equal))

(defun %layout (type)
  (java:field "java.lang.foreign.ValueLayout"
              (case type
                ((:char :unsigned-char :uchar :int8 :uint8 :bool) "JAVA_BYTE")
                ((:short :unsigned-short :int16 :uint16) "JAVA_SHORT")
                ((:int :unsigned-int :int32 :uint32) "JAVA_INT")
                ((:float) "JAVA_FLOAT")
                ((:double) "JAVA_DOUBLE")
                ((:pointer :string) "ADDRESS")
                (t "JAVA_LONG"))))

(defun %descriptor (rettype argtypes)
  ;; An empty argument list cannot be passed through java: interop (nil marshals
  ;; to null, not to an empty array), so the no-argument shape is built without it.
  (let ((layouts (mapcar #'%layout argtypes)))
    (cond ((and (eq rettype :void) (null layouts))
           (java:static "java.lang.foreign.FunctionDescriptor" "ofVoid"))
          ((eq rettype :void)
           (java:static "java.lang.foreign.FunctionDescriptor" "ofVoid" layouts))
          ((null layouts)
           (java:static "java.lang.foreign.FunctionDescriptor" "of" (%layout rettype)))
          (t
           (java:static "java.lang.foreign.FunctionDescriptor" "of" (%layout rettype) layouts)))))

(defvar *lookups* '())

(defun %lookups (library)
  "A flat namespace: the process lookup first, then every loaded library."
  (declare (ignore library))
  (cons *default-lookup* (reverse *lookups*)))

(defun %segment (address)
  (java:static "java.lang.foreign.MemorySegment" "ofAddress" address))

(defun %address (segment)
  (java:call segment "address"))

(defun %box (type value)
  "Box VALUE as the exact Java type the downcall's method type demands."
  (case type
    ((:char :unsigned-char :uchar :int8 :uint8 :bool)
     (java:static "java.lang.Byte" "valueOf" (java:static "java.lang.String" "valueOf" value)))
    ((:short :unsigned-short :int16 :uint16)
     (java:static "java.lang.Short" "valueOf" (java:static "java.lang.String" "valueOf" value)))
    ((:int :unsigned-int :int32 :uint32)
     (java:static "java.lang.Integer" "valueOf" (java:static "java.lang.String" "valueOf" value)))
    ((:float) (java:static "java.lang.Float" "valueOf" (java:static "java.lang.String" "valueOf" value)))
    ((:double) value)
    ((:pointer) (%segment value))
    ((:string) (if (null value)
                   (java:field "java.lang.foreign.MemorySegment" "NULL")
                   (java:call *arena* "allocateFrom" value)))
    (t (java:static "java.lang.Long" "valueOf" (java:static "java.lang.String" "valueOf" value)))))

(defun %unbox (type value)
  (case type
    ((:void) nil)
    ((:pointer) (%address value))
    ((:string) (if (zerop (%address value))
                   nil
                   (java:call (java:call value "reinterpret" 9223372036854775807) "getString" 0)))
    (t value)))

(defun %downcall (address rettype argtypes)
  (let* ((key (list address rettype argtypes))
         (cached (gethash key *handles*)))
    (or cached
        (setf (gethash key *handles*)
              (java:call *linker* "downcallHandle" (%segment address) (%descriptor rettype argtypes))))))

(defun %call-address (address rettype argtypes args)
  (let ((handle (%downcall address rettype argtypes)))
    (%unbox rettype
            (if (null argtypes)
                (java:call handle "invokeWithArguments" (java:static "java.util.List" "of"))
                (java:call handle "invokeWithArguments" (mapcar #'%box argtypes args))))))

(defun %call-name (name library rettype argtypes args)
  (%call-address (%symbol-address name library) rettype argtypes args))

;;;# C ABI utils

(defun canonicalize-symbol-name-case (name)
  (string-upcase name))

;;;# Pointers

(deftype foreign-pointer () 'integer)

(defun pointerp (ptr) (integerp ptr))

(defun pointer-eq (ptr1 ptr2) (eql ptr1 ptr2))

(defun null-pointer () 0)

(defun null-pointer-p (ptr) (zerop ptr))

(defun inc-pointer (ptr offset) (+ ptr offset))

(defun make-pointer (address) address)

(defun pointer-address (ptr) ptr)

;;;# Allocation

(defun %foreign-alloc (size)
  (%address (java:call *arena* "allocate" size)))

(defun foreign-free (ptr)
  (declare (ignore ptr))
  nil)

(defmacro with-foreign-pointer ((var size &optional size-var) &body body)
  (unless size-var (setf size-var (gensym "SIZE")))
  `(let* ((,size-var ,size)
          (,var (%foreign-alloc ,size-var)))
     (declare (ignorable ,size-var))
     (unwind-protect (progn ,@body)
       (foreign-free ,var))))

;;;# Shareable vectors

(defun make-shareable-byte-vector (size)
  (make-array size :element-type '(unsigned-byte 8)))

(defmacro with-pointer-to-vector-data ((ptr-var vector) &body body)
  (let ((v (gensym "VECTOR")) (i (gensym "I")))
    `(let* ((,v ,vector)
            (,ptr-var (%foreign-alloc (length ,v))))
       (dotimes (,i (length ,v))
         (%mem-set (aref ,v ,i) ,ptr-var :unsigned-char ,i))
       (unwind-protect (progn ,@body)
         (dotimes (,i (length ,v))
           (setf (aref ,v ,i) (%mem-ref ,ptr-var :unsigned-char ,i)))))))

;;;# Dereferencing

(defun %mem-ref (ptr type &optional (offset 0))
  (let ((seg (java:call (%segment (+ ptr offset)) "reinterpret" 9223372036854775807)))
    (%unbox (if (eq type :string) :pointer type)
            (java:call seg "get" (%layout type) 0))))

(defun %mem-set (value ptr type &optional (offset 0))
  (let ((seg (java:call (%segment (+ ptr offset)) "reinterpret" 9223372036854775807)))
    (java:call seg "set" (%layout type) 0 (%box type value))
    value))

;;;# Foreign types

(defun %type-bytes (type)
  (case type
    ((:char :unsigned-char :uchar :int8 :uint8 :bool :void) 1)
    ((:short :unsigned-short :int16 :uint16) 2)
    ((:int :unsigned-int :int32 :uint32 :float) 4)
    (t 8)))

(defun %foreign-type-size (type) (%type-bytes type))

(defun %foreign-type-alignment (type) (%type-bytes type))

;;;# Calling

(defun %parse-args (args)
  "Split cffi's (type value ... rettype) into (values types values rettype)."
  (let ((types '()) (values '()) (rettype :void))
    (loop while args
          do (let ((type (pop args)))
               (cond ((null args) (setf rettype type))
                     ((eq type '&optional))
                     (t (push type types) (push (pop args) values)))))
    (values (nreverse types) (nreverse values) rettype)))

(defmacro %foreign-funcall (name args &key library convention)
  (declare (ignore convention))
  (multiple-value-bind (types values rettype) (%parse-args args)
    `(%call-name ,name ,(if (or (null library) (eq library :default)) nil `',library)
                 ',rettype ',types (list ,@values))))

(defmacro %foreign-funcall-pointer (ptr args &key convention)
  (declare (ignore convention))
  (multiple-value-bind (types values rettype) (%parse-args args)
    `(%call-address ,ptr ',rettype ',types (list ,@values))))

(defmacro %foreign-funcall-varargs (name fixed-args varargs &rest args &key convention library)
  (declare (ignore convention library))
  `(%foreign-funcall ,name ,(append fixed-args varargs) ,@args))

(defmacro %foreign-funcall-pointer-varargs (pointer fixed-args varargs &rest args &key convention)
  (declare (ignore convention))
  `(%foreign-funcall-pointer ,pointer ,(append fixed-args varargs) ,@args))

;;;# Callbacks

(defvar *callbacks* (make-hash-table :test 'equal))

(defmacro %defcallback (name rettype arg-names arg-types body &key convention)
  (declare (ignore convention))
  `(progn
     (setf (gethash ',name *callbacks*)
           (%make-callback (lambda ,arg-names ,body) ',rettype ',arg-types))
     (%callback ',name)))

(defun %callback (name)
  (or (gethash name *callbacks*) (error "Undefined callback: ~S" name)))

;;;# Libraries

(defun %load-foreign-library (name path)
  (declare (ignore name))
  (let* ((key (namestring path))
         (lookup (java:static "java.lang.foreign.SymbolLookup" "libraryLookup" key *arena*)))
    (setf (gethash key *libraries*) lookup)
    (push lookup *lookups*)
    key))

(defun %close-foreign-library (handle)
  (remhash handle *libraries*)
  t)

(defun native-namestring (pathname)
  (namestring pathname))

;;;# Foreign globals

(defun %find-symbol (name library)
  (dolist (lookup (%lookups library))
    (let ((found (java:call lookup "find" name)))
      (when (java:call found "isPresent")
        (return (%address (java:call found "get")))))))

(defun %symbol-address (name library)
  (or (%find-symbol name library)
      (error "Undefined foreign symbol: ~A" name)))

(defun %foreign-symbol-pointer (name library)
  (%find-symbol name library))

;;;# Compiler macro utils

(defun constant-form-p (form)
  (declare (ignore form))
  nil)

(defun constant-form-value (form)
  form)

(defun %make-callback (function rettype argtypes)
  (declare (ignore function rettype argtypes))
  (error "SPIKE: callbacks are not wired through java: interop"))

;;;# SPIKE: the babel shim gaps cffi/strings.lisp needs
(deftype babel::unicode-string () 'string)
(deftype babel::simple-unicode-string () 'string)
(deftype babel::unicode-char () 'character)
(defun babel::string-get (string index) (char-code (char string index)))
(defun babel::string-set (code string index) (setf (char string index) (code-char code)))
