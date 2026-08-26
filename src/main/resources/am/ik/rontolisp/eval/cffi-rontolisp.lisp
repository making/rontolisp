;; The cffi-sys backend for rontolisp: upstream CFFI's own implementation seam
;; -- the ~30 names its src/package.lisp exports -- written over the ffi:
;; primitives (.kb/ffi.md) exactly as upstream's src/cffi-sbcl.lisp is written
;; over SB-ALIEN. Everything ABOVE this file (the type system, defcfun,
;; defcstruct, the enum/bitfield layer, the translate and expand protocols) is
;; upstream's portable source, loaded unmodified from the release tarball; this
;; file ships as a resource and is spliced in as the implementation component
;; (cffi-rontolisp.asd + ShimLibraries.leafModuleForms), so upstream's tree on
;; disk is never edited.
;;
;; Three things differ from the backends written before FFM existed:
;;
;; - FLAT NAMESPACE. SBCL and most others push cffi-features:flat-namespace and
;;   let a call find a symbol whichever library it came from. FFM has no such
;;   namespace: SymbolLookup.libraryLookup is per library, and the default
;;   lookup never sees a library opened after it. So this file keeps the opened
;;   handles in load order and searches them -- which is what makes
;;   use-foreign-library followed by a plain defcfun behave the way every
;;   binding in the ecosystem assumes.
;; - STRUCTURES BY VALUE NEED NO LIBFFI. ffi:call takes a (:struct member...)
;;   designator and builds the layout itself, so *foreign-structures-by-value*
;;   below is the ordinary call path with the struct's slots translated, and
;;   upstream's "Unable to call structures by value without cffi-libffi loaded"
;;   restart can never fire here. cffi-libffi stays unloadable, permanently.
;; - WITH-POINTER-TO-VECTOR-DATA COPIES IN AND OUT. Nothing here can pin a Lisp
;;   vector, so the body sees a fresh foreign buffer whose bytes are copied back
;;   into the vector on exit -- the contract the other unpinnable backends
;;   document. A pointer kept past the body is dangling.

(in-package #:cffi-sys)

;;;# Misfeatures

(pushnew 'flat-namespace *features*)

;;;# Symbol Case

(defun canonicalize-symbol-name-case (name) (string-upcase name))

;;;# Basic Pointer Operations

;;; A pointer is the ffi: package's own foreign-pointer value rather than an
;;; integer, so ffi:pointerp answers nil for 42 and a wrong operand at the
;;; foreign boundary stays a type error. ffi:address is its own inverse
;;; (pointer -> integer, integer -> pointer), which is why make-pointer,
;;; pointer-address and null-pointer are all one verb.
(deftype foreign-pointer () '(satisfies ffi:pointerp))

(defun pointerp (ptr)
  "Return true if PTR is a foreign pointer."
  (ffi:pointerp ptr))

(defun pointer-eq (ptr1 ptr2)
  "Return true if PTR1 and PTR2 point to the same address."
  (= (ffi:address ptr1) (ffi:address ptr2)))

(defun null-pointer ()
  "Construct and return a null pointer."
  (ffi:address 0))

(defun null-pointer-p (ptr)
  "Return true if PTR is a null pointer."
  (zerop (ffi:address ptr)))

(defun inc-pointer (ptr offset)
  "Return a pointer pointing OFFSET bytes past PTR."
  (ffi:address (+ (ffi:address ptr) offset)))

(defun make-pointer (address)
  "Return a pointer pointing to ADDRESS."
  (ffi:address address))

(defun pointer-address (ptr)
  "Return the address pointed to by PTR."
  (ffi:address ptr))

;;;# Allocation
;;;
;;; ffi:alloc is malloc and ffi:free is free: foreign memory outlives every
;;; Lisp scope and is released explicitly, which is CFFI's own contract.

(defun %foreign-alloc (size)
  "Allocate SIZE bytes on the heap and return a pointer."
  (ffi:alloc size))

(defun foreign-free (ptr)
  "Free a PTR allocated by FOREIGN-ALLOC."
  (ffi:free ptr))

(defmacro with-foreign-pointer ((var size &optional size-var) &body body)
  "Bind VAR to SIZE bytes of foreign memory during BODY.  The pointer in
VAR is invalid beyond the dynamic extent of BODY.  If SIZE-VAR is
supplied, it will be bound to SIZE during BODY.  Nothing is
stack-allocated here: every buffer is malloc'd and freed on exit."
  (unless size-var (setf size-var (gensym "SIZE")))
  `(let* ((,size-var ,size) (,var (%foreign-alloc ,size-var)))
     (declare (ignorable ,size-var))
     (unwind-protect (progn ,@body) (foreign-free ,var))))

;;;# Shareable Vectors

(defun make-shareable-byte-vector (size)
  "Create a Lisp vector of SIZE bytes that can be passed to
WITH-POINTER-TO-VECTOR-DATA."
  (make-array size :element-type '(unsigned-byte 8)))

(defmacro with-pointer-to-vector-data ((ptr-var vector) &body body)
  "Bind PTR-VAR to a foreign COPY of VECTOR's data.  No backend here can pin a
Lisp vector, so the bytes are copied in before BODY and copied back out after
it: what the foreign side writes does reach VECTOR, but only once BODY has
returned, and the pointer is freed with it."
  (let ((vector-var (gensym "VECTOR"))
        (count-var (gensym "COUNT"))
        (index-var (gensym "INDEX")))
    `(let* ((,vector-var ,vector)
            (,count-var (length ,vector-var))
            (,ptr-var (%foreign-alloc ,count-var)))
       (dotimes (,index-var ,count-var)
         (%mem-set (aref ,vector-var ,index-var) ,ptr-var
                   :unsigned-char ,index-var))
       (unwind-protect (progn ,@body)
         (dotimes (,index-var ,count-var)
           (setf (aref ,vector-var ,index-var)
                 (%mem-ref ,ptr-var :unsigned-char ,index-var)))
         (foreign-free ,ptr-var)))))

;;;# Dereferencing

(defun %mem-ref (ptr type &optional (offset 0)) (ffi:peek ptr type offset))

(defun %mem-set (value ptr type &optional (offset 0))
  (ffi:poke ptr type value offset))

;;;# Foreign Types
;;;
;;; The cffi type keywords ARE the ffi: type designators: both name the C type
;;; and both read the C integer names as their LP64 widths (.kb/ffi.md).

(defun %foreign-type-size (type-keyword)
  "Return the size in bytes of a foreign type."
  (ffi:size type-keyword))

(defun %foreign-type-alignment (type-keyword)
  "Return the alignment in bytes of a foreign type."
  (ffi:align type-keyword))

;;;# Foreign Libraries

(defvar *libraries*
  '()
  "The ffi:open handles of the libraries %load-foreign-library opened, in load
order.  The process's own symbols come first and are not in the list: opening
the process is a foreign operation, and loading this system must not need one
-- a machine that denies native access still gets the whole type system, and
fails at the first call.  This list plus the process IS the flat namespace --
see the header.")

(defvar *symbols*
  (make-hash-table :test 'equal)
  "Foreign symbol name to its address.  A downcall costs about half a
microsecond, so the lookup walk must not dominate it.  Only hits are cached:
a symbol that appears when a later library is opened is still found.")

(defun native-namestring (pathname) (namestring pathname))

(defun %load-foreign-library (name path)
  "Load a foreign library and answer its handle."
  (declare (ignore name))
  (let ((handle (ffi:open (native-namestring path))))
    ;; Appended, not pushed: the search order is load order, so a symbol an
    ;; earlier library already defines keeps answering from there.
    (setf *libraries* (append *libraries* (list handle)))
    handle))

(defun %close-foreign-library (handle)
  "Close a foreign library: it leaves the search order.  FFM cannot unmap a
library once opened, so the code stays resident and a pointer into it stays
valid; what this undoes is the flat-namespace membership."
  (setf *libraries* (remove handle *libraries*))
  (clrhash *symbols*)
  t)

;;;# Foreign Globals

(defun %search-order () (cons (ffi:open) *libraries*))

(defun %foreign-symbol-pointer (name library)
  "Return a pointer to the foreign symbol NAME, or nil when no loaded library
has it.  LIBRARY is ignored: the namespace is flat."
  (declare (ignore library))
  (or (gethash name *symbols*)
      (dolist (handle (%search-order))
        (let ((address (ffi:symbol handle name)))
          (when address (return (setf (gethash name *symbols*) address)))))))

(defun %symbol-address (name)
  (or (%foreign-symbol-pointer name nil)
      (error "Undefined foreign symbol: ~A" name)))

;;;# Calling Foreign Functions

(defvar *shapes*
  (make-hash-table :test 'equal)
  "One call SHAPE -- the canonicalized cffi return type consed onto its
argument types -- to the (return . arguments) ffi: designators it translates
to.  Memoized because ffi:call caches its downcall handle by the designators
it receives (~24 us to build one, ~0.5 us to call it), so the same key has to
reach it on every call of a given shape.")

(defun %ffi-type (type)
  "The ffi: designator for one canonicalized cffi type.  A scalar keyword
already is one; a structure BY VALUE becomes (:struct member...), its members
being the slot types in offset order.  That translation is the whole of this
backend's structures-by-value support."
  (cond ((and (consp type) (eq (first type) :struct)) (%struct-designator type))
        ((and (consp type) (eq (first type) :union))
         (error
          "cffi-sys: a union cannot be passed or returned by value here~
 -- the foreign function API lays out structures only"))
        ((consp type)
         (error "cffi-sys: no foreign designator for the aggregate type ~S"
                type))
        (t type)))

(defun %struct-designator (type)
  (let* ((parsed (cffi::ensure-parsed-base-type type))
         (designator
          (cons :struct (mapcar (lambda (slot)
                                  (%ffi-type
                                   (cffi::canonicalize-foreign-type
                                    (cffi::slot-type slot))))
                                (cffi::slots-in-order parsed)))))
    ;; cffi lays a structure out itself and accepts per-slot :offset overrides;
    ;; the foreign function API lays it out from the member list with the C
    ;; padding rule. Agreeing on size and alignment is what says the two
    ;; layouts are the same one -- a hand-offset or bitfield struct does not,
    ;; and must not be passed by value on a guess.
    (unless (and (= (ffi:size designator) (cffi:foreign-type-size type))
                 (= (ffi:align designator) (cffi:foreign-type-alignment type)))
      (error "cffi-sys: ~S cannot be passed by value -- cffi lays it out as ~
~D bytes aligned ~D, the foreign function API as ~D aligned ~D" type
             (cffi:foreign-type-size type) (cffi:foreign-type-alignment type)
             (ffi:size designator) (ffi:align designator)))
    designator))

(defun %shape (rettype argtypes)
  (let ((key (cons rettype argtypes)))
    (or (gethash key *shapes*)
        (setf (gethash key *shapes*)
              (cons (%ffi-type rettype) (mapcar #'%ffi-type argtypes))))))

(defun %call-address (address rettype argtypes args)
  (let ((shape (%shape rettype argtypes)))
    (apply #'ffi:call address (car shape) (cdr shape) args)))

(defun %call-symbol (name rettype argtypes args)
  (%call-address (%symbol-address name) rettype argtypes args))

(defun foreign-funcall-type-and-args (args)
  "Split cffi's (type value ... type value rettype) into three values: the
argument types, the argument forms, and the return type.  &optional -- the
variadic-tail marker cffi's own varargs expansion may insert -- becomes the
ffi: :varargs marker, which is the same statement."
  (let ((types '()) (fargs '()) (rettype :void))
    (loop while args
          do
            (let ((type (pop args)))
              (cond ((null args) (setf rettype type))
                    ((eq type '&optional) (push :varargs types))
                    (t
                     (push type types)
                     (push (pop args) fargs)))))
    (values (nreverse types) (nreverse fargs) rettype)))

(defmacro %foreign-funcall (name args &rest options)
  "Perform a foreign function call.  The types are known here, at expansion,
so the expansion quotes them and ffi:call sees one key per shape.  The
:library and :convention options are ignored: the namespace is flat and cdecl
is the only convention a System V or AAPCS target has."
  (declare (ignore options))
  (multiple-value-bind (types fargs rettype)
      (foreign-funcall-type-and-args args)
    `(%call-symbol ,name ',rettype ',types (list ,@fargs))))

(defmacro %foreign-funcall-pointer (ptr args &rest options)
  "Funcall a pointer to a foreign function."
  (declare (ignore options))
  (multiple-value-bind (types fargs rettype)
      (foreign-funcall-type-and-args args)
    `(%call-address ,ptr ',rettype ',types (list ,@fargs))))

(defmacro %foreign-funcall-varargs (name fixed-args varargs &rest options)
  "A variadic call.  The fixed prefix and the variadic tail arrive separately,
so the marker goes exactly where the tail starts -- without it the call is
silently wrong on AArch64 and Apple silicon."
  (declare (ignore options))
  `(%foreign-funcall ,name
    ,(append fixed-args (and varargs (list '&optional)) varargs)))

(defmacro %foreign-funcall-pointer-varargs
    (pointer fixed-args varargs &rest options)
  (declare (ignore options))
  `(%foreign-funcall-pointer ,pointer
    ,(append fixed-args (and varargs (list '&optional)) varargs)))

;;;# Structures by Value
;;;
;;; Upstream's default signals and offers a restart that loads cffi-libffi, a
;;; system built around a C library. Here a struct type simply translates to
;;; an ffi: (:struct member...) designator (see %ffi-type), so the call is the
;;; ordinary one and nothing extra has to be loaded. Set with defparameter
;;; ahead of functions.lisp: its own defvar then leaves this value in place,
;;; which is how a backend loaded BEFORE the file that declares the variable
;;; gets to decide it.
(defparameter cffi::*foreign-structures-by-value*
  (lambda (thing fargs syms types rettype ctypes pointerp)
    (cffi::translate-objects syms fargs types rettype
                             `(,(if pointerp
                                    '%foreign-funcall-pointer
                                    '%foreign-funcall) ,thing
                               (,@(mapcan #'list ctypes syms)
                                ,(cffi::canonicalize-foreign-type rettype)))))
  "A function producing a form that calls a function taking or returning a
structure by value.")

;;;# Callbacks

(defvar *callbacks*
  (make-hash-table :test 'equal)
  "Callback name to the code address ffi:callback answered for it.")

(defmacro %defcallback (name rettype arg-names arg-types body &rest options)
  (declare (ignore options))
  `(progn
     ;; Redefining a callback answers a NEW address: a foreign upcall stub
     ;; cannot be re-targeted once linked, so a C side that already holds the
     ;; old address keeps calling the old definition.
     (setf (gethash ',name *callbacks*)
           (ffi:callback (lambda ,arg-names ,body) ',rettype ',arg-types))
     (%callback ',name)))

(defun %callback (name)
  (or (gethash name *callbacks*) (error "Undefined callback: ~S" name)))
