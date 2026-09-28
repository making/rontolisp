;; The objc: and cocoa: packages: LispWorks 8.1's Objective-C and Cocoa interface
;; vocabulary (invoke, invoke-bool, invoke-into, retain / release, the autorelease
;; pools, the selector and class coercions, cocoa:ns-rect ...) plus this package's own
;; data / bytes / objectp, written ONCE here and run on every target (see
;; ObjcLibrary.java and .kb/objc.md). The interpreter loads it on the first use of an
;; objc: name; the compile path splices it into a JVM class or a --native executable.
;;
;; Everything a target contributes is the primitive layer under objc::% -- the runtime
;; queries (%get-class, %class-name, %object-class, %class-p, %register-selector,
;; %selector-name, %method-types), the one call (%send: receiver, SEL, the encoding,
;; the variadic split, RAW arguments, a mode), the ownership handle (%new-handle,
;; %refs), the table of live pointers (%interned, %intern), the exception a call raised
;; (%raised) and the byte copies (%octets, %write-octets, %read-octets). Every
;; conversion between a Lisp value and an Objective-C one, and every ownership rule, is
;; in this file.
;;
;; Portability constraints honored here (like appkit.lisp): do loops always declare
;; at least one variable; parameters are never assigned with setq.

;;; --- the values -------------------------------------------------------------------

;; An Objective-C object: its address, and the handle that counts the references this
;; program holds through it (objc::%new-handle). The interpreter and the JVM keep one value per
;; address (%intern), so two answers for one object are eq; a --native module cannot
;; (no weak references) and its backend compares this type by the address slot alone.
(defstruct (objc:objc-object-pointer
            (:constructor objc::%make-pointer (address handle))
            (:predicate objc::%pointerp) (:conc-name objc::%pointer-)
            (:copier nil))
  address
  handle)

;; A class: an object that is never released, interned per address on every target.
(defstruct (objc:objc-class (:include objc:objc-object-pointer)
                            (:constructor objc::%make-class (address handle))
                            (:predicate objc::%classp)
                            (:conc-name objc::%class-struct-) (:copier nil)))

;; A selector, interned per name.
(defstruct (objc:sel (:constructor objc::%make-sel (address name))
                     (:predicate objc::%selp) (:conc-name objc::%sel-struct-)
                     (:copier nil))
  address
  name)

;; An emulated autorelease pool: the pointers whose references it holds.
(defstruct (objc::autorelease-pool (:constructor objc::%make-pool ())
                                   (:predicate objc::%poolp)
                                   (:conc-name objc::%pool-) (:copier nil))
  (pointers nil))

;; What objc:current-super answers inside a method defined in Lisp: the receiver's address
;; and the class a send to it starts its lookup in (the defining class's superclass).
(defstruct (objc::super-ref
            (:constructor objc::%make-super-ref (receiver class))
            (:predicate objc::%super-ref-p) (:conc-name objc::%super-)
            (:copier nil))
  receiver
  class)

;; LispWorks' FLI pointer: an address of foreign memory and the FLI type of what it points
;; at. Every pointer-typed value Objective-C hands Lisp is one (an invoke result, a
;; callback's argument, a dereferenced pointer), typed by the encoding's pointee, and
;; fli:allocate-foreign-object makes one over calloc'd memory -- the host's, never a wasm
;; module's linear memory. ENCODING is the pointee's, what %peek and %poke read and write
;; it by. Not interned and not compared by address: fli:pointer-eq does that.
(defstruct (fli::pointer
            (:constructor objc::%make-foreign-pointer (address type encoding))
            (:predicate fli:pointerp) (:conc-name fli::%pointer-) (:copier nil))
  address
  type
  encoding)

;; The definition half (objc-class.lisp) plugs in here: a standard-objc-object or a Lisp
;; class that implements an Objective-C class stands for its pointer wherever one is
;; taken, and a pointer maps back to the Lisp object made for it. Nil while that half is
;; not loaded, so a program that only calls pays nothing.
(defvar objc::*object-pointer-hook* nil)

(defvar objc::*pointer-object-hook* nil)

;; The pointer of the Lisp object registered for an address: one value per such object
;; even where pointers are not interned (--native), so its references are counted once.
(defvar objc::*registered-pointer-hook* nil)

;; Run once the runtime is up: realizes the classes defined before that.
(defvar objc::*realize-hook* nil)

;; The blocks half (objc-block.lisp) plugs in here: an objc:objc-block stands for its
;; literal's address wherever a block or a pointer is taken. Nil while that half is not
;; loaded.
(defvar objc::*block-pointer-hook* nil)

(defvar objc::*initialized* nil)

;; define-objc-struct and define-objc-typedef: a type name -> its encoding, and a
;; structure's foreign name -> its type name.
(defvar objc::*type-encodings* (make-hash-table :test 'equal))

(defvar objc::*struct-names* (make-hash-table :test 'equal))

;; A structure type name -> its slots, ((slot-name fli-type) ...), what
;; fli:foreign-slot-value and fli:size-of lay it out by: the four Foundation structures
;; here, define-objc-struct's as they are defined. Slot names match by symbol name, so
;; :width, cocoa::width and a user's width all name NSSize's.
(defvar objc::*struct-slots* (make-hash-table :test 'equal))

(setf (gethash 'cocoa:ns-point objc::*struct-slots*)
      '((:x :double) (:y :double)))

(setf (gethash 'cocoa:ns-size objc::*struct-slots*)
      '((:width :double) (:height :double)))

(setf (gethash 'cocoa:ns-rect objc::*struct-slots*)
      '((:origin cocoa:ns-point) (:size cocoa:ns-size)))

(setf (gethash 'cocoa:ns-range objc::*struct-slots*)
      '((:location (:unsigned :long-long)) (:length (:unsigned :long-long))))

;; LispWorks' printed form of a foreign pointer. The address only: printing must never
;; be what touches a freed object (the message of a refused release prints the pointer).
(defun objc::%print-pointer (type address stream)
  (format stream "#<Pointer: ~a = #x~16,'0x>" type
          (objc::%unsigned address 64)))

(defmethod print-object ((object objc:objc-object-pointer) stream)
  (objc::%print-pointer
   (if (objc::%classp object) "OBJC:OBJC-CLASS" "OBJC:OBJC-OBJECT-POINTER")
   (objc::%pointer-address object) stream))

(defmethod print-object ((object objc:sel) stream)
  (objc::%print-pointer "OBJC:SEL" (objc::%sel-struct-address object) stream))

(defmethod print-object ((pointer fli::pointer) stream)
  (format stream "#<Pointer to type ~s = #x~16,'0x>"
          (fli::%pointer-type pointer)
          (objc::%unsigned (fli::%pointer-address pointer) 64)))

(defmethod print-object ((object objc::autorelease-pool) stream)
  (format stream "#<autorelease-pool ~a>"
          (length (objc::%pool-pointers object))))

(defvar objc::*classes* (make-hash-table))

(defvar objc::*classes-by-name* (make-hash-table :test 'equal))

(defvar objc::*selectors* (make-hash-table :test 'equal))

(defvar objc::*selectors-by-address* (make-hash-table))

(defvar objc::*autorelease-pools* nil)

(defvar objc::*traced* (make-hash-table :test 'equal))

;; Method name -> ((lookup-class-address . signature) ...).
(defvar objc::*signatures* (make-hash-table :test 'equal))

(defun objc::%intern-class (address)
  (or (gethash address objc::*classes*)
      (setf (gethash address objc::*classes*) (objc::%make-class address nil))))

(defun objc::%intern-sel (address name)
  (let ((sel (objc::%make-sel address name)))
    (setf (gethash name objc::*selectors*) sel)
    (setf (gethash address objc::*selectors-by-address*) sel)
    sel))

(defun objc::%sel-for-address (address)
  (if (= address 0)
      nil
      (or (gethash address objc::*selectors-by-address*)
          (objc::%intern-sel address (objc::%selector-name address)))))

;; The one live value for an address, if there is one.
(defun objc::%live-pointer (address)
  (or (objc::%interned address)
      (and objc::*registered-pointer-hook*
           (funcall objc::*registered-pointer-hook* address))))

;; The value for an object address this program now holds ONE reference to (retained in
;; the hop, or handed over by the alloc/new/copy family).
(defun objc::%wrap-object (address)
  (cond ((= address 0) nil)
        ((objc::%class-p address) (objc::%intern-class address))
        (t (let ((existing (objc::%live-pointer address)))
             (if existing
                 (progn
                   (objc::%refs (objc::%pointer-handle existing) 0 1)
                   existing)
                 (objc::%intern address
                                (objc::%make-pointer address
                                 (objc::%new-handle address 1))))))))

;; A value standing for a pointer: the pointer itself, else whatever the definition half
;; maps it to (a standard-objc-object's pointer, a Lisp class's Objective-C class), else nil.
(defun objc::%as-pointer (value)
  (cond ((objc::%pointerp value) value)
   (objc::*object-pointer-hook* (funcall objc::*object-pointer-hook* value))
   (t nil)))

;; A pointer value that holds no reference: the live one for the address when there is
;; one, else a fresh one with nothing to release -- what a method's receiver is.
(defun objc::%borrow (address)
  (cond ((= address 0) nil)
        ((objc::%class-p address) (objc::%intern-class address))
        (t (or (objc::%live-pointer address)
               (objc::%intern address
                (objc::%make-pointer address (objc::%new-handle address 0)))))))

;; The runtime is up, and every class defined before that exists.
(defun objc::%ready ()
  (unless objc::*initialized*
    (objc::%initialize)
    (setq objc::*initialized* t)
    (when objc::*realize-hook* (funcall objc::*realize-hook*))))

;;; --- classes and selectors --------------------------------------------------------

(defun objc:coerce-to-objc-class (class)
  (cond ((objc::%classp class) class)
        ((stringp class)
         (or (gethash class objc::*classes-by-name*)
             (let ((address
                    (progn
                      (objc::%ready)
                      (objc::%get-class class))))
               (when (= address 0)
                 (error
                  "objc:coerce-to-objc-class: no Objective-C class named ~a"
                  class))
               (setf (gethash class objc::*classes-by-name*)
                     (objc::%intern-class address)))))
        ((objc::%classp (objc::%as-pointer class)) (objc::%as-pointer class))
        (t (error
            "objc:coerce-to-objc-class: ~s is neither a class name nor a class"
            class))))

(defun objc:objc-class-name (class)
  (cond
   ((objc::%classp class) (objc::%class-name (objc::%pointer-address class)))
   ((stringp class)
    (objc::%class-name
     (objc::%pointer-address (objc:coerce-to-objc-class class))))
   (t (error "objc:objc-class-name: ~s is not a class" class))))

(defun objc:coerce-to-selector (method)
  (cond ((objc::%selp method) method)
        ((stringp method)
         (or (gethash method objc::*selectors*)
             (objc::%intern-sel (objc::%register-selector method) method)))
        (t
         (error "objc:coerce-to-selector: ~s is neither a string nor a selector"
                method))))

(defun objc:selector-name (selector)
  (cond ((stringp selector) selector)
        ((objc::%selp selector) (objc::%sel-struct-name selector))
        (t (error "objc:selector-name: ~s is neither a string nor a selector"
                  selector))))

(defun objc::%sel-address (name)
  (objc::%sel-struct-address (objc:coerce-to-selector name)))

;;; --- type encodings ---------------------------------------------------------------

;; A parsed type is a keyword -- :void :object :class :sel :cstring :block :unknown
;; :bool (B) :int8 (c) :uint8 :int16 :uint16 :int32 :uint32 :long (l) :ulong :int64
;; :uint64 :float :double -- or a list: (:pointer pointee), (:struct name members)
;; with the struct's members as parsed (nested; objc::%leaves flattens them), (:array
;; count element), (:union name), (:bitfield width). The encoding grammar is Apple's
;; (Type Encodings, the Objective-C Runtime Programming Guide), the one
;; TypeEncoding.java and encoding.rs read for the call itself.

(defun objc::%skip-digits (types pos)
  (let ((n (length types)) (p pos))
    (loop while (and (< p n) (digit-char-p (char types p))) do (setq p (+ p 1)))
    p))

;; Past the <...> Clang's extended encoding writes after a block's @?: the block's own
;; signature, nested for a block taking a block.
(defun objc::%skip-block-signature (types pos)
  (let ((n (length types)) (p pos) (depth 0))
    (when (and (< p n) (char= (char types p) #\<))
      (loop while (< p n)
            do
              (let ((c (char types p)))
                (setq p (+ p 1))
                (cond ((char= c #\<) (setq depth (+ depth 1)))
                      ((char= c #\>)
                       (setq depth (- depth 1))
                       (when (= depth 0) (return)))))))
    p))

;; (type . next-position)
(defun objc::%parse-type (types pos)
  (let ((n (length types)) (p pos))
    (loop while (and (< p n) (find (char types p) "rnNoORVA"))
          do (setq p (+ p 1)))
    (when (>= p n) (error "objc: type encoding ~s is truncated" types))
    (let ((c (char types p)))
      (setq p (+ p 1))
      (case c
        (#\@ (cond ((and (< p n) (char= (char types p) #\?))
                    (cons :block (objc::%skip-block-signature types (+ p 1))))
                   ((and (< p n) (char= (char types p) #\"))
                    (cons :object (+ (position #\" types :start (+ p 1)) 1)))
                   (t (cons :object p))))
        (#\# (cons :class p))
        (#\: (cons :sel p))
        (#\* (cons :cstring p))
        (#\? (cons :unknown p))
        (#\v (cons :void p))
        (#\B (cons :bool p))
        (#\c (cons :int8 p))
        (#\C (cons :uint8 p))
        (#\s (cons :int16 p))
        (#\S (cons :uint16 p))
        (#\i (cons :int32 p))
        (#\I (cons :uint32 p))
        (#\l (cons :long p))
        (#\L (cons :ulong p))
        (#\q (cons :int64 p))
        (#\Q (cons :uint64 p))
        (#\f (cons :float p))
        (#\d (cons :double p))
        (#\^ (let ((pointee (objc::%parse-type types p)))
               (cons (list :pointer (car pointee)) (cdr pointee))))
        (#\{ (objc::%parse-aggregate types p #\} :struct))
        (#\( (objc::%parse-aggregate types p #\) :union))
        (#\[ (let* ((q (objc::%skip-digits types p))
                    (count (parse-integer types :start p :end q))
                    (element (objc::%parse-type types q)))
               (cons (list :array count (car element)) (+ (cdr element) 1))))
        (#\b (let ((q (objc::%skip-digits types p)))
               (cons (list :bitfield (parse-integer types :start p :end q)) q)))
        (t (error "objc: type encoding ~s: unsupported type ~s" types c))))))

;; {name=members} / (name=members); a pointee may have no member list ({CGRect}).
(defun objc::%parse-aggregate (types pos close kind)
  (let* ((n (length types)) (p pos) (start pos) (members nil))
    (loop while
            (and (< p n) (char/= (char types p) #\=)
                 (char/= (char types p) close))
          do (setq p (+ p 1)))
    (let ((name (subseq types start p)))
      (when (and (< p n) (char= (char types p) #\=))
        (setq p (+ p 1))
        (loop while (and (< p n) (char/= (char types p) close))
              do
                (when (char= (char types p) #\")
                  (setq p (+ (position #\" types :start (+ p 1)) 1)))
                (let ((member (objc::%parse-type types p)))
                  (setq p (cdr member))
                  (push (car member) members))))
      (when (>= p n) (error "objc: type encoding ~s: expected ~s" types close))
      (cons (if (eq kind :struct)
                (list :struct name (nreverse members))
                (list :union name)) (+ p 1)))))

;; A type's scalar leaves in memory order: what a structure value holds, one per leaf.
(defun objc::%leaves (type)
  (cond ((and (consp type) (eq (car type) :struct))
         (let ((out nil))
           (dolist (member (third type) out)
             (setq out (append out (objc::%leaves member))))))
        ((and (consp type) (eq (car type) :array))
         (let ((out nil))
           (dotimes (i (second type) out)
             (setq out (append out (objc::%leaves (third type)))))))
        ((consp type) (list :pointer))
        (t (list type))))

;; (return-type argument-types...) of a whole method encoding, receiver and selector
;; included.
(defun objc::%parse-encoding (types)
  (let ((pos 0) (out nil) (n (length types)))
    (loop while (< pos n)
          do
            (let ((parsed (objc::%parse-type types pos)))
              (push (car parsed) out)
              (setq pos (objc::%skip-digits types (cdr parsed)))))
    (nreverse out)))

;; The spelling handed to %send: a block or a function pointer travels as the plain
;; pointer it is (objc-at-question-mark is an alias for :pointer), which every host
;; parser reads.
(defun objc::%callable-types (types)
  (let ((out (make-string-output-stream)) (n (length types)) (i 0))
    (loop while (< i n)
          do
            (let ((c (char types i)))
              (cond ((and (< (+ i 1) n) (or (char= c #\@) (char= c #\^))
                          (char= (char types (+ i 1)) #\?))
                     (write-string "^v" out)
                     (setq i (objc::%skip-block-signature types (+ i 2))))
                    (t
                     (write-char c out)
                     (setq i (+ i 1))))))
    (get-output-stream-string out)))

(defun objc::%struct-kind (type)
  (and (consp type) (eq (car type) :struct)
       (let ((name (second type)))
         (cond ((member name '("CGRect" "NSRect" "_NSRect") :test #'string=)
                :ns-rect)
               ((member name '("CGPoint" "NSPoint" "_NSPoint") :test #'string=)
                :ns-point)
               ((member name '("CGSize" "NSSize" "_NSSize") :test #'string=)
                :ns-size)
               ((member name '("_NSRange" "NSRange") :test #'string=) :ns-range)
               (t :other)))))

;; What objc-class-method-signature answers for a parsed type: the FLI type descriptor
;; LispWorks names.
(defun objc::%fli-type (type)
  (if (consp type)
      (case (car type)
        (:pointer (let ((pointee (objc::%fli-type (second type))))
                    (if (eq pointee :void) :pointer (list :pointer pointee))))
        (:struct
         (list :struct (case (objc::%struct-kind type)
                         (:ns-rect 'cocoa:ns-rect)
                         (:ns-point 'cocoa:ns-point)
                         (:ns-size 'cocoa:ns-size)
                         (:ns-range 'cocoa:ns-range)
                         (t (or (gethash (second type) objc::*struct-names*)
                                (second type))))))
        (:array (list :c-array (objc::%fli-type (third type)) (second type)))
        (:union (list :union (second type)))
        (t (list :bitfield (second type))))
      (case type
        (:object 'objc:objc-object-pointer)
        (:class 'objc:objc-class)
        (:sel 'objc:sel)
        (:cstring 'objc:objc-c-string)
        (:block 'objc:objc-at-question-mark)
        (:unknown 'objc:objc-unknown)
        (:bool 'objc:objc-c++-bool)
        (:int8 '(:signed :char))
        (:uint8 '(:unsigned :char))
        (:int16 :short)
        (:uint16 '(:unsigned :short))
        (:int32 :int)
        (:uint32 '(:unsigned :int))
        (:long :long)
        (:ulong '(:unsigned :long))
        (:int64 :long-long)
        (:uint64 '(:unsigned :long-long))
        (:float :float)
        (:double :double)
        (t :void))))

;; The inverse: an FLI type a list-form method names, as its encoding.
(defun objc::%type-encoding (fli)
  (or (objc::%encoding-or-nil fli)
      (error "objc: ~s is not an FLI type this interface can call" fli)))

;; An FLI type's encoding, or nil for one this interface does not know.
(defun objc::%encoding-or-nil (fli)
  (cond ((consp fli)
         (let ((head (car fli)))
           (cond ((eq head :pointer)
                  (if (cdr fli)
                      (let ((pointee (objc::%encoding-or-nil (second fli))))
                        (and pointee (concatenate 'string "^" pointee)))
                      "^v"))
                 ((and (member head '(:unsigned :signed)) (cdr fli))
                  (let ((base (objc::%encoding-or-nil (second fli))))
                    (and base
                         (if (eq head :unsigned) (string-upcase base) base))))
                 ((eq head :boolean) "B")
                 ((and (eq head :struct) (symbolp (second fli)) (second fli))
                  (objc::%encoding-or-nil (second fli)))
                 ((and (eq head :c-array) (cdr fli) (integerp (third fli)))
                  (let ((element (objc::%encoding-or-nil (second fli))))
                    (and element (format nil "[~a~a]" (third fli) element))))
                 (t nil))))
        ((member fli '(:void)) "v")
        ((member fli '(:char :byte :int8)) "c")
        ((member fli '(:uint8)) "C")
        ((member fli '(:short :int16)) "s")
        ((member fli '(:uint16)) "S")
        ((member fli '(:int :int32)) "i")
        ((member fli '(:uint32)) "I")
        ((member fli
                 '(:long :long-long :int64 :intptr :intmax :ptrdiff-t :ssize-t))
         "q")
        ((member fli '(:uint64 :uintptr :size-t)) "Q")
        ((member fli '(:float :single-float)) "f")
        ((member fli '(:double :double-float)) "d")
        ((member fli '(:boolean)) "B")
        ((member fli '(:pointer)) "^v")
        ((eq fli 'objc:objc-object-pointer) "@")
        ((eq fli 'objc:objc-class) "#")
        ((eq fli 'objc:sel) ":")
        ((eq fli 'objc:objc-c-string) "*")
        ((eq fli 'objc:objc-bool) "c")
        ((eq fli 'objc:objc-c++-bool) "B")
        ((eq fli 'objc:objc-at-question-mark) "@?")
        ((eq fli 'objc:objc-unknown) "?")
        ((eq fli 'cocoa:ns-rect) "{CGRect={CGPoint=dd}{CGSize=dd}}")
        ((eq fli 'cocoa:ns-point) "{CGPoint=dd}")
        ((eq fli 'cocoa:ns-size) "{CGSize=dd}")
        ((eq fli 'cocoa:ns-range) "{_NSRange=QQ}")
        (t (gethash fli objc::*type-encodings*))))

;; A parsed type spelled back as an encoding (a structure by its nested members, which
;; every host lays out by the C rule), for a pointee whose FLI type has no encoding of its
;; own.
(defun objc::%unparse (type)
  (if (consp type)
      (case (car type)
        (:pointer (concatenate 'string "^" (objc::%unparse (second type))))
        (:struct (let ((out (make-string-output-stream)))
                   (write-string "{" out)
                   (write-string (second type) out)
                   (write-string "=" out)
                   (dolist (member (third type))
                     (write-string (objc::%unparse member) out))
                   (write-string "}" out)
                   (get-output-stream-string out)))
        (:array
         (format nil "[~a~a]" (second type) (objc::%unparse (third type))))
        (:union (format nil "(~a)" (second type)))
        (t (format nil "b~a" (second type))))
      (case type
        (:void "v")
        (:object "@")
        (:class "#")
        (:sel ":")
        (:cstring "*")
        (:block "@?")
        (:bool "B")
        (:int8 "c")
        (:uint8 "C")
        (:int16 "s")
        (:uint16 "S")
        (:int32 "i")
        (:uint32 "I")
        (:long "l")
        (:ulong "L")
        (:int64 "q")
        (:uint64 "Q")
        (:float "f")
        (:double "d")
        (t "?"))))

;; A scalar's size, and a parsed type's by the C rule over its nested members (the layout
;; every host uses for a structure it parsed).
(defun objc::%leaf-size (leaf)
  (case leaf
    ((:bool :int8 :uint8) 1)
    ((:int16 :uint16) 2)
    ((:int32 :uint32 :float) 4)
    (t 8)))

(defun objc::%type-alignment (type)
  (cond ((and (consp type) (eq (car type) :struct))
         (let ((align 1))
           (dolist (member (third type) align)
             (setq align (max align (objc::%type-alignment member))))))
        ((and (consp type) (eq (car type) :array))
         (objc::%type-alignment (third type)))
        (t (objc::%leaf-size (if (consp type) :pointer type)))))

(defun objc::%type-size (type)
  (cond ((and (consp type) (eq (car type) :struct))
         (let ((offset 0) (align (objc::%type-alignment type)))
           (dolist (member (third type))
             (let ((a (objc::%type-alignment member)))
               (setq offset (* a (ceiling offset a)))
               (setq offset (+ offset (objc::%type-size member)))))
           (* align (ceiling offset align))))
        ((and (consp type) (eq (car type) :array))
         (* (second type) (objc::%type-size (third type))))
        ((eq type :void) 0)
        (t (objc::%leaf-size (if (consp type) :pointer type)))))

;; The named slots of a structure FLI type -- cocoa:ns-rect, (:struct name), or the name
;; define-objc-struct gave one -- or nil.
(defun objc::%struct-slots (fli)
  (let ((name
         (cond ((and (consp fli) (eq (car fli) :struct)) (second fli))
               ((symbolp fli) fli)
               (t nil))))
    (and name (gethash name objc::*struct-slots*))))

;; (size . alignment) of an FLI type, by the C rule: a structure with named slots slot by
;; slot, anything else by its parsed encoding.
(defun objc::%fli-layout (fli)
  (let ((slots (objc::%struct-slots fli)))
    (if slots
        (let ((offset 0) (align 1))
          (dolist (slot slots)
            (let* ((layout (objc::%fli-layout (second slot))) (a (cdr layout)))
              (setq offset (+ (* a (ceiling offset a)) (car layout)))
              (setq align (max align a))))
          (cons (* align (ceiling offset align)) align))
        (let ((parsed (car (objc::%parse-type (objc::%type-encoding fli) 0))))
          (cons (objc::%type-size parsed) (objc::%type-alignment parsed))))))

;; A variadic argument's encoding: C's default promotions (a float travels as a
;; double), and then every integer in a whole 64-bit slot -- a variadic argument takes
;; one on every supported ABI, the callee reads its own width out of it, and so the shape
;; stays one a native image registers (void*, jlong, jdouble).
(defun objc::%promoted-encoding (fli)
  (let ((e (objc::%type-encoding fli)))
    (cond ((string= e "f") "d")
     ((member e '("c" "C" "s" "S" "B" "i" "I" "l" "L" "Q") :test #'string=) "q")
     (t e))))

;;; --- signatures ---------------------------------------------------------------

;; A PLAN is everything invoke needs that depends only on the method, worked out once
;; per (class, name): #(types return-type argument-types sel mode init-p performs-p),
;; the argument types being the method's own (no receiver, no selector), TYPES the
;; spelling %send takes and MODE the %send mode bits for the result.
(defun objc::%plan (raw sel name)
  (let* ((parsed (objc::%parse-encoding raw))
         (return-type (first parsed))
         (performs (objc::%family-p name "performSelector"))
         (owned (objc::%owned-result-p name)))
    (vector (objc::%callable-types raw) return-type (cdddr parsed) sel
            (if (and (eq return-type :object) (not owned) (not performs)) 1 0)
            (objc::%family-p name "init") performs)))

(defun objc::%lookup-class (address) (objc::%object-class address))

(defun objc::%missing-method (target cls name)
  (error "No method ~s for object ~s, class ~s." name
         (if (stringp target) (objc:coerce-to-objc-class target) target)
         (objc::%class-name cls)))

(defun objc::%lookup-plan (target cls name)
  (let* ((entries (gethash name objc::*signatures*)) (hit (assoc cls entries)))
    (if hit
        (cdr hit)
        (let* ((sel (objc::%sel-address name))
               (raw (objc::%method-types cls sel)))
          (unless raw (objc::%missing-method target cls name))
          (let ((plan (objc::%plan raw sel name)))
            (setf (gethash name objc::*signatures*)
                  (cons (cons cls plan) entries))
            plan)))))

;; The list form of a method: (name arg-types &key result-type variadic-num-of-fixed),
;; as the encoding it describes and the variadic split (-1: not variadic).
(defun objc::%list-types (method)
  (let* ((name (first method))
         (arg-types (second method))
         (options (cddr method))
         (result-type (or (getf options :result-type) :void))
         (fixed (getf options :variadic-num-of-fixed))
         (encoding (make-string-output-stream)))
    (unless (stringp name)
      (error "objc:invoke: a method is a string or (name arg-types &key result-type variadic-num-of-fixed), got ~s"
             method))
    (write-string (objc::%type-encoding result-type) encoding)
    (write-string "@:" encoding)
    (let ((i 0))
      (dolist (fli arg-types)
        (write-string (if (and fixed (>= i fixed))
                          (objc::%promoted-encoding fli)
                          (objc::%type-encoding fli)) encoding)
        (setq i (+ i 1))))
    (values (get-output-stream-string encoding) (if fixed fixed -1))))

;; The list form's plan and variadic split.
(defun objc::%list-plan (method)
  (multiple-value-bind (types fixed) (objc::%list-types method)
    (values
     (objc::%plan types (objc::%sel-address (first method)) (first method))
     fixed)))

;; The selectors declared exactly like a fixed-arity twin but variadic
;; (arrayWithObjects: is @@:@ like arrayWithObject:). The string form of such a
;; selector takes arguments past the declared arity, each typed by its value, and a nil
;; terminator is appended -- the nil-terminated half needs it and the format half never
;; reads it; calling one as fixed-arity would read its va_list off a slot nobody wrote.
(defvar objc::*variadic-selectors*
  '("arrayWithObjects:" "initWithObjects:" "setWithObjects:"
    "orderedSetWithObjects:" "dictionaryWithObjectsAndKeys:"
    "initWithObjectsAndKeys:" "stringWithFormat:" "initWithFormat:"
    "localizedStringWithFormat:" "stringByAppendingFormat:" "appendFormat:"
    "predicateWithFormat:" "raise:format:"))

(defun objc::%value-encoding (value)
  (cond ((integerp value) "q")
        ((floatp value) "d")
        ((rationalp value) "d")
        (t "@")))

(defun objc::%value-type (value)
  (cond ((integerp value) :int64) ((realp value) :double) (t :object)))

;;; --- ownership ----------------------------------------------------------------------

;; ARC's method families: the word at the start (leading underscores ignored), followed
;; by the end or a character that is not a lowercase letter.
(defun objc::%family-p (name family)
  (let* ((start (or (position #\_ name :test-not #'char=) 0))
         (end (+ start (length family))))
    (and (<= end (length name)) (string= family name :start2 start :end2 end)
         (or (= end (length name)) (not (lower-case-p (char name end)))))))

(defun objc::%owned-result-p (name)
  (or (objc::%family-p name "alloc") (objc::%family-p name "new")
      (objc::%family-p name "copy") (objc::%family-p name "mutableCopy")
      (objc::%family-p name "init")))

;; The init family consumes the receiver's reference: one the collector would have
;; released, else an explicit one -- taken without a message, since the method itself
;; gave it up.
(defun objc::%consume (pointer)
  (when (and (objc::%pointerp pointer) (not (objc::%classp pointer)))
    (let ((handle (objc::%pointer-handle pointer)))
      (when (< (objc::%refs handle 0 -1) 0) (objc::%refs handle 1 -1)))))

(defun objc::%give-up (pointer verb)
  (let ((handle (objc::%pointer-handle pointer)))
    (unless (or (>= (objc::%refs handle 1 -1) 0)
                (>= (objc::%refs handle 0 -1) 0))
      (error "objc:~a: ~s holds no reference this program can give up" verb
             pointer))))

(defun objc::%message (pointer name)
  (or (objc::%send (objc::%pointer-address pointer) (objc::%sel-address name)
                   "v16@0:8" -1 nil 0) (objc::%checked name pointer)))

(defun objc:retain (object)
  (let ((pointer (or (objc::%as-pointer object) object)))
    (objc::%retain pointer)
    object))

(defun objc::%retain (pointer)
  (cond ((objc::%classp pointer) pointer)
        ((objc::%pointerp pointer)
         (objc::%message pointer "retain")
         (objc::%refs (objc::%pointer-handle pointer) 1 1)
         pointer)
        (t (error "objc:retain: ~s is not an Objective-C object" pointer))))

(defun objc:release (object)
  (objc::%release
   (if (objc::%poolp object) object (or (objc::%as-pointer object) object))))

(defun objc::%release (pointer)
  (cond ((objc::%poolp pointer) (objc::%drain pointer))
        ((objc::%classp pointer) nil)
        ((objc::%pointerp pointer)
         (objc::%give-up pointer "release")
         (objc::%message pointer "release")
         nil)
        (t (error "objc:release: ~s is not an Objective-C object" pointer))))

(defun objc:autorelease (object)
  (objc::%autorelease (or (objc::%as-pointer object) object))
  object)

(defun objc::%autorelease (pointer)
  (cond ((objc::%classp pointer) pointer)
   ((objc::%pointerp pointer)
    (objc::%give-up pointer "autorelease")
    (let ((pool (first objc::*autorelease-pools*)))
      (if pool
          (progn
            (objc::%refs (objc::%pointer-handle pointer) 2 1)
            (push pointer (objc::%pool-pointers pool)))
          (objc::%refs (objc::%pointer-handle pointer) 0 1)))
    pointer)
   (t (error "objc:autorelease: ~s is not an Objective-C object" pointer))))

(defun objc:retain-count (object)
  (let ((pointer (or (objc::%as-pointer object) object)))
    (objc::%retain-count pointer)))

(defun objc::%retain-count (pointer)
  (unless (objc::%pointerp pointer)
    (error "objc:retain-count: ~s is not an Objective-C object" pointer))
  (or (objc::%send (objc::%pointer-address pointer)
                   (objc::%sel-address "retainCount") "Q16@0:8" -1 nil 0)
      (objc::%checked "retainCount" pointer)))

(defun objc::%drain (pool)
  (let ((live (member pool objc::*autorelease-pools*)))
    (when live
      (dolist (inner objc::*autorelease-pools*)
        (objc::%empty inner)
        (when (eq inner pool) (return)))
      (setq objc::*autorelease-pools* (rest live))))
  nil)

(defun objc::%empty (pool)
  (let ((pointers (reverse (objc::%pool-pointers pool))))
    (setf (objc::%pool-pointers pool) nil)
    (dolist (pointer pointers)
      (objc::%refs (objc::%pointer-handle pointer) 2 -1)
      (objc::%message pointer "release"))))

(defun objc:make-autorelease-pool ()
  (let ((pool (objc::%make-pool)))
    (push pool objc::*autorelease-pools*)
    pool))

(defun objc::%call-with-autorelease-pool (function)
  (let* ((pool (objc::%make-pool))
         (objc::*autorelease-pools* (cons pool objc::*autorelease-pools*)))
    (unwind-protect (funcall function) (objc::%empty pool))))

;;; --- invoke -------------------------------------------------------------------------

(defun objc::%target-address (target)
  (cond ((objc::%pointerp target) (objc::%pointer-address target))
        ((stringp target)
         (objc::%pointer-address (objc:coerce-to-objc-class target)))
        ((objc::%as-pointer target)
         (objc::%pointer-address (objc::%as-pointer target)))
        (t (error "objc:invoke: the receiver must be an object, a class or a class name, got ~s"
                  target))))

(defun objc::%arg-error (name index expected value)
  (error "objc:invoke: ~a: argument ~a must be ~a, got ~s" name (+ index 1)
         expected value))

;; An NSArray for a vector, each element converted as an id argument. Answers the
;; pointer, which the caller releases when the send returns.
(defun objc::%ns-array (vector temps)
  (let ((array
         (objc:invoke "NSMutableArray" "arrayWithCapacity:" (length vector))))
    (dotimes (i (length vector))
      (let ((element (aref vector i)))
        (or (objc::%send (objc::%pointer-address array)
             (objc::%sel-address "addObject:") "v24@0:8@16" -1
             (list (objc::%raw-arg :object element "addObject:" 0 temps)) 0)
            (objc::%checked "addObject:" array))))
    array))

(defun objc::%leaf-values (value count name index expected)
  (unless (and (vectorp value) (not (stringp value)) (= (length value) count))
    (objc::%arg-error name index expected value))
  (let ((out nil))
    (dotimes (i count)
      (let ((leaf (aref value i)))
        (unless (realp leaf) (objc::%arg-error name index expected value))
        (push
         (if (integerp leaf) (objc::%bits64 leaf name index) (float leaf 1d0))
         out)))
    (nreverse out)))

;; One argument as %send takes it, by the declared type. TEMPS is a cell whose car
;; collects the objects made for the call, released when it returns.
(defun objc::%raw-arg (type value name index temps)
  (if (consp type)
      (case (car type)
        (:struct
         (case (if (fli:pointerp value) :foreign (objc::%struct-kind type))
           ;; The manual: "otherwise it is assumed to be a foreign pointer to a
           ;; cocoa:ns-rect and is copied" -- for every structure.
           (:foreign (objc::%peek (objc::%foreign-address value name index)
                                  (objc::%unparse type)))
           (:ns-rect (objc::%leaf-values value 4 name index
                                         "a vector #(x y width height)"))
           (:ns-point (objc::%leaf-values value 2 name index "a vector #(x y)"))
           (:ns-size
            (objc::%leaf-values value 2 name index "a vector #(width height)"))
           (:ns-range
            (unless (and (consp value) (integerp (car value))
                         (integerp (cdr value)))
              (objc::%arg-error name index "a cons (location . length)" value))
            (list (objc::%bits64 (car value) name index)
                  (objc::%bits64 (cdr value) name index)))
           (t
            (let ((n (length (objc::%leaves type))))
              (objc::%leaf-values value n name index
                                  (format nil "a vector of the ~a leaves of ~a"
                                          n (second type)))))))
        (:pointer (objc::%raw-address value name index))
        (t (error "objc:invoke: ~a: argument ~a is a ~a, which this interface cannot pass"
                  name (+ index 1) (car type))))
      (case type
        (:object (cond ((null value) 0)
                       ((stringp value) value)
                       ((objc::%pointerp value) (objc::%pointer-address value))
                       ((fli:pointerp value) (fli::%pointer-address value))
                       ((vectorp value)
                        (let ((array (objc::%ns-array value temps)))
                          (push array (car temps))
                          (objc::%pointer-address array)))
                       ((objc::%as-pointer value)
                        (objc::%pointer-address (objc::%as-pointer value)))
                       ((objc::%block-address value))
                       (t (objc::%arg-error name index
                           "an object, a string, a vector or nil" value))))
        (:class
         (cond ((null value) 0)
          ((objc::%pointerp value) (objc::%pointer-address value))
          ((stringp value)
           (objc::%pointer-address (objc:coerce-to-objc-class value)))
          ((objc::%as-pointer value)
           (objc::%pointer-address (objc::%as-pointer value)))
          (t (objc::%arg-error name index "a class or a class name" value))))
        (:sel
         (cond ((null value) 0)
          ((or (stringp value) (objc::%selp value))
           (objc::%sel-struct-address (objc:coerce-to-selector value)))
          (t (objc::%arg-error name index "a selector or its name" value))))
        (:cstring (cond ((null value) 0)
                        ((stringp value) value)
                        ((integerp value) (objc::%bits64 value name index))
                        ((fli:pointerp value) (fli::%pointer-address value))
                        (t (objc::%arg-error name index "a string" value))))
        (:block
         (when (functionp value)
           (error "objc:invoke: ~a: argument ~a is a block; a Lisp function becomes one with objc:make-objc-block or objc:with-objc-block, which name its signature -- the method's encoding does not"
                  name (+ index 1)))
         (objc::%raw-address value name index))
        (:unknown (objc::%raw-address value name index))
        (:bool
         (cond ((null value) 0) ((integerp value) (if (= value 0) 0 1)) (t 1)))
        (:int8 (cond ((null value) 0)
                ((eq value t) 1)
                ((integerp value) (objc::%bits64 value name index))
                (t (objc::%arg-error name index "an integer, t or nil" value))))
        ((:float :double)
         (if (realp value)
             (float value 1d0)
             (objc::%arg-error name index "a number" value)))
        (t (if (integerp value)
               (objc::%bits64 value name index)
               (objc::%arg-error name index "an integer" value))))))

;; An integer as the 64 bits a register carries: an unsigned value past the signed range
;; travels as its two's complement, which is what every host reads.
(defun objc::%bits64 (value name index)
  (cond
   ((and (>= value -9223372036854775808) (< value 9223372036854775808)) value)
   ((and (>= value 0) (< value 18446744073709551616))
    (- value 18446744073709551616))
   (t (objc::%arg-error name index "an integer that fits in 64 bits" value))))

(defun objc::%raw-address (value name index)
  (cond ((null value) 0)
        ((integerp value) (objc::%bits64 value name index))
        ((fli:pointerp value)
         (objc::%bits64 (fli::%pointer-address value) name index))
        ((objc::%pointerp value) (objc::%pointer-address value))
        ((objc::%selp value) (objc::%sel-struct-address value))
        ((objc::%block-address value))
        (t (objc::%arg-error name index
            "a pointer (a foreign pointer, an integer or an object)" value))))

;; The address of a foreign pointer whose memory is read, which must not be null.
(defun objc::%foreign-address (pointer name index)
  (let ((address (fli::%pointer-address pointer)))
    (when (= address 0)
      (error "objc:invoke: ~a: argument ~a is a null pointer" name (+ index 1)))
    address))

;; The literal's address of a block made in Lisp, or nil for anything else.
(defun objc::%block-address (value)
  (and objc::*block-pointer-hook* (funcall objc::*block-pointer-hook* value)))

(defun objc::%unsigned (value bits)
  (if (< value 0) (+ value (expt 2 bits)) value))

(defun objc::%leaf-value (leaf value)
  (case leaf
    ((:uint64 :ulong) (objc::%unsigned value 64))
    (:uint32 (objc::%unsigned value 32))
    (:uint16 (objc::%unsigned value 16))
    (:uint8 (objc::%unsigned value 8))
    (t value)))

(defun objc::%struct-leaves (type raw)
  (let ((out nil) (kinds (objc::%leaves type)))
    (dolist (value raw)
      (push (objc::%leaf-value (car kinds) value) out)
      (setq kinds (cdr kinds)))
    (nreverse out)))

;; The answer of a send as invoke returns it.
(defun objc::%result (type raw)
  (if (consp type)
      (case (car type)
        (:struct (let ((leaves (objc::%struct-leaves type raw)))
                   (if (eq (objc::%struct-kind type) :ns-range)
                       (cons (first leaves) (second leaves))
                       (coerce leaves 'simple-vector))))
        (:pointer
         (let ((pointee (second type)))
           (objc::%foreign-pointer-to raw (objc::%fli-type pointee) pointee)))
        (t raw))
      (case type
        (:void nil)
        (:object (objc::%wrap-object raw))
        (:class (if (= raw 0) nil (objc::%intern-class raw)))
        (:sel (objc::%sel-for-address raw))
        (:unknown nil)
        ((:uint64 :ulong) (objc::%unsigned raw 64))
        (t raw))))

(defvar objc::*tracing* nil)

(defun objc::%trace (target name args)
  (format *trace-output* "~&(objc:invoke ~s ~s~{ ~s~})~%" target name args))

;; retain, release and autorelease sent to an object go through the reference counts,
;; so no spelling of them bypasses the rule that a pointer gives up only what it holds.
(defun objc::%counted-message-p (target method args)
  (and (null args) (stringp method) (not (objc::%super-ref-p target))
       (objc::%as-pointer target)
       (not (objc::%classp (objc::%as-pointer target)))
       (or (string= method "retain") (string= method "release")
           (string= method "autorelease"))))

;; The one send every invoke variant goes through. INTO is how invoke-into wants the
;; answer, or nil; OUT is true when the last argument is the address of an NSError *
;; slot, whose object the host retains before the send's pool drains (invoke-with-error).
(defun objc::%invoke (target method args into out)
  (cond ((null target) nil)
        ((objc::%counted-message-p target method args)
         (let ((pointer (objc::%as-pointer target)))
           (cond ((string= method "retain") (objc::%retain pointer))
                 ((string= method "release") (objc::%release pointer))
                 (t (objc::%autorelease pointer)))))
        (t
         (unless objc::*initialized* (objc::%ready))
         (let* ((superp (objc::%super-ref-p target))
                (address
                 (if superp
                     (objc::%super-receiver target)
                     (objc::%target-address target)))
                (listed (consp method))
                (fixed -1)
                (plan nil))
           (if listed
               (multiple-value-bind (p f) (objc::%list-plan method)
                 (setq plan p fixed f))
               (progn
                 (unless (stringp method)
                   (error "objc:invoke: a method is a string or (name arg-types &key result-type variadic-num-of-fixed), got ~s"
                          method))
                 (setq plan
                       (objc::%lookup-plan target
                                           (if superp
                                               (objc::%super-class target)
                                               (objc::%lookup-class address))
                                           method))))
           (let* ((name (if listed (first method) method))
                  (types (svref plan 0))
                  (return-type (svref plan 1))
                  (params (svref plan 2))
                  (declared (length params)))
             ;; Even with no argument past the declared ones: the nil terminator is
             ;; what a one-element arrayWithObjects: needs, and a format callee never
             ;; reads it.
             (when (and (not listed) (>= (length args) declared)
                    (member name objc::*variadic-selectors* :test #'string=))
               (let ((extra (nthcdr declared args)) (spelled types))
                 (dolist (value extra)
                   (setq spelled
                         (concatenate 'string spelled
                                      (objc::%value-encoding value))))
                 (setq types (concatenate 'string spelled "@"))
                 (setq params
                       (append params (mapcar #'objc::%value-type extra)
                               (list :object)))
                 (setq args (append args (list nil)))
                 (setq fixed declared)
                 (setq declared (length params))))
             (unless (= (length args) declared)
               (error "objc:invoke: ~a takes ~a argument(s), got ~a" name
                      declared (length args)))
             ;; A list-form variadic call gets the same trailing nil: no callee reads
             ;; past its own format or terminator, and a variadic list that ends in an
             ;; address is a shape the native binary registers.
             (when (and listed (>= fixed 0))
               (setq types (concatenate 'string types "@"))
               (setq params (append params (list :object)))
               (setq args (append args (list nil))))
             (when (and objc::*tracing* (gethash name objc::*traced*))
               (objc::%trace target name args))
             (let* ((temps (list nil))
                    (mode
                     (if (and (eq return-type :cstring)
                              (or (eq into :pointer) (fli:pointerp into)))
                         2
                         (if out (logior (svref plan 4) 4) (svref plan 4))))
                    (raw
                     (unwind-protect (let ((raws nil) (i 0))
                                       (dolist (value args)
                                         (push (objc::%raw-arg (car params)
                                                               value name i
                                                               temps) raws)
                                         (setq params (cdr params))
                                         (setq i (+ i 1)))
                                       (or (if superp
                                               (objc::%send-super address
                                                (objc::%super-class target)
                                                (svref plan 3) types fixed
                                                (nreverse raws) mode)
                                               (objc::%send address
                                                            (svref plan 3) types
                                                            fixed
                                                            (nreverse raws)
                                                            mode))
                                           (objc::%checked name target)))
                       (when (car temps)
                         (dolist (temp (car temps)) (objc:release temp))))))
               (when (svref plan 5) (objc::%consume target))
               (let ((value
                      (if (svref plan 6) nil (objc::%result return-type raw))))
                 (when (and objc::*tracing* (gethash name objc::*traced*))
                   (format *trace-output* "~&  => ~s~%" value))
                 (if into (objc::%into into return-type value) value))))))))

(defun objc:invoke (class-or-object-pointer method &rest args)
  (objc::%invoke class-or-object-pointer method args nil nil))

(defun objc:invoke-bool (class-or-object-pointer method &rest args)
  (let ((value (objc::%invoke class-or-object-pointer method args nil nil)))
    (if (or (null value) (eql value 0)) nil t)))

;;; --- what Objective-C reports itself: exceptions and NSError ------------------------

;; An Objective-C exception raised inside a send or a C call and caught there by the host
;; (.kb/objc.md, "Exceptions and NSError"). The call answered nothing; the
;; object is the thrown one, whose reference the condition holds.
(define-condition objc:objc-exception (error)
  ((name :initarg :name :reader objc:objc-exception-name)
   (reason :initarg :reason :initform nil :reader objc:objc-exception-reason)
   (object :initarg :object :initform nil :reader objc:objc-exception-object)
   (where :initarg :where :initform nil :reader objc::%exception-where))
  (:report
   (lambda (condition stream)
     (format stream "~@[~a ~]raised ~a~@[: ~a~]"
             (objc::%exception-where condition)
             (objc:objc-exception-name condition)
             (objc:objc-exception-reason condition)))))

;; A method invoke-with-error called reported failure through its NSError **.
(define-condition objc:ns-error (error)
  ((domain :initarg :domain :reader objc:ns-error-domain)
   (code :initarg :code :reader objc:ns-error-code)
   (description :initarg :description :reader objc:ns-error-description)
   (object :initarg :object :reader objc:ns-error-object)
   (where :initarg :where :initform nil :reader objc::%ns-error-where))
  (:report
   (lambda (condition stream)
     (format stream "~@[~a ~]failed: ~a (~a ~a)"
             (objc::%ns-error-where condition)
             (objc:ns-error-description condition)
             (objc:ns-error-domain condition) (objc:ns-error-code condition)))))

;; How a message names the call: +name to a class, -name to an instance, a C function's
;; own name.
;; TARGET is the receiver, or :class, :instance or :function where a caller knows.
(defun objc::%where (name target)
  (cond ((eq target :function) name)
        ((eq target :instance) (concatenate 'string "-" name))
        ((or (eq target :class) (stringp target)
             (objc::%classp (objc::%as-pointer target)))
         (concatenate 'string "+" name))
        (t (concatenate 'string "-" name))))

;; Every %send, %send-super and %call-function is written (or (call ...) (objc::%checked
;; name target)): a call that raised answered nil and the host kept what it threw until
;; %raised reads it, so a nil answer is checked here and a raise signals objc-exception.
;; NAME and TARGET name the call in the report.
(defun objc::%checked (name target)
  (let ((thrown (objc::%raised)))
    (if thrown (objc::%signal-exception thrown name target) nil)))

(defun objc::%ns-string-value (pointer)
  (if pointer (objc:ns-string-to-string pointer) nil))

(defun objc::%signal-exception (address name target)
  (let* ((object (objc::%wrap-object address))
         (exceptionp
          (and object
               (objc:invoke-bool object "isKindOfClass:"
                                 (objc:coerce-to-objc-class "NSException")))))
    (error 'objc:objc-exception
           :name (cond
                  (exceptionp
                   (objc::%ns-string-value (objc:invoke object "name")))
                  (object (objc::%class-name (objc::%object-class address)))
                  (t "nil"))
           :reason (and exceptionp
                        (objc::%ns-string-value (objc:invoke object "reason")))
           :object object
           :where (objc::%where name target))))

;; calloc and free: the NSError * slot invoke-with-error hands a method, and every
;; foreign object fli allocates. Host memory, on every target.
(defvar objc::*calloc* nil)

(defvar objc::*free* nil)

(defun objc::%c-function (name)
  (let ((address (objc::%symbol-address name)))
    (when (= address 0) (error "objc: ~a is missing" name))
    address))

(defun objc::%calloc (count size)
  (unless objc::*initialized* (objc::%ready))
  (unless objc::*calloc*
    (setq objc::*calloc* (objc::%c-function "calloc"))
    (setq objc::*free* (objc::%c-function "free")))
  (let ((address
         (or (objc::%call-function objc::*calloc* "^vQQ" -1 (list count size) 0)
             (objc::%checked "calloc" :function))))
    (when (= address 0)
      (error "objc: calloc could not allocate ~a byte(s)" (* count size)))
    address))

(defun objc::%free (address)
  (or (objc::%call-function objc::*free* "v^v" -1 (list address) 0)
      (objc::%checked "free" :function))
  nil)

(defun objc:invoke-with-error (class-or-object-pointer method &rest args)
  (let ((name (if (consp method) (first method) method)))
    (unless (and (stringp name) (>= (length name) 6)
                 (string= "error:" name :start2 (- (length name) 6)))
      (error "objc:invoke-with-error: ~s does not end in \"error:\"; invoke-with-error supplies the NSError ** parameter, which is the last one, itself"
             name))
    (let ((slot (objc::%calloc 1 8)))
      (unwind-protect (let* ((result
                              (objc::%invoke class-or-object-pointer method
                                             (append args (list slot)) nil t))
                             (written
                              (objc::%wrap-object (objc::%peek slot "^v"))))
                        (if (and written (or (null result) (eql result 0)))
                            (error 'objc:ns-error
                             :domain (objc::%ns-string-value
                                      (objc:invoke written "domain"))
                             :code (objc:invoke written "code")
                             :description
                             (objc::%ns-string-value
                              (objc:invoke written "localizedDescription"))
                             :object written
                             :where (objc::%where name class-or-object-pointer))
                            result))
        (objc::%free slot)))))

;;; --- invoke-into ------------------------------------------------------------------

(defun objc:ns-string-to-string (ns-string &optional preserve-line-terminators)
  (unless (objc::%pointerp ns-string)
    (error "objc:ns-string-to-string: ~s is not an Objective-C object"
           ns-string))
  (let ((text (or (objc::%invoke ns-string "UTF8String" nil nil nil) "")))
    (if preserve-line-terminators text (objc::%lines-of text))))

;; CR LF and a lone CR become a newline; LF stays one.
(defun objc::%lines-of (text)
  (if (not (find (code-char 13) text))
      text
      (let ((out (make-string-output-stream)) (n (length text)) (i 0))
        (loop while (< i n)
              do
                (let ((c (char text i)))
                  (cond ((char/= c (code-char 13)) (write-char c out))
                        ((and (< (+ i 1) n)
                              (char= (char text (+ i 1)) (code-char 10))))
                        (t (write-char (code-char 10) out)))
                  (setq i (+ i 1))))
        (get-output-stream-string out))))

(defun objc:string-to-ns-string (string &optional autoreleasep)
  (unless (stringp string)
    (error "objc:string-to-ns-string: ~s is not a string" string))
  (let ((ns-string
         (objc::%invoke "NSString" "stringWithUTF8String:" (list string) nil
                        nil)))
    (if autoreleasep (objc:autorelease ns-string) ns-string)))

(defun objc::%array-elements (pointer element)
  (let* ((n (objc::%invoke pointer "count" nil nil nil)) (out (make-array n)))
    (dotimes (i n out)
      (setf (aref out i)
            (objc::%convert-element
             (objc::%invoke pointer "objectAtIndex:" (list i) nil nil)
             element)))))

(defun objc::%convert-element (value element)
  (cond ((null value) nil)
        ((eq element 'string) (objc:ns-string-to-string value))
        ((eq element 'array) (objc::%array-elements value nil))
        ((and (consp element) (eq (car element) 'array))
         (objc::%array-elements value (second element)))
        (t value)))

(defun objc::%into (into return-type value)
  (cond ((fli:pointerp into)
         ;; LispWorks: the result is copied into the foreign object.
         (unless (eq return-type :void)
           (objc::%poke (objc::%non-null into "objc:invoke-into")
            (objc::%unparse return-type)
            (objc::%raw-arg return-type value "objc:invoke-into" 0 (list nil))))
         into)
        ((eq return-type :object)
         (cond ((null value) nil)
               ((eq into 'string) (objc:ns-string-to-string value))
               ((eq into 'array) (objc::%array-elements value nil))
               ((and (consp into) (eq (car into) 'array))
                (objc::%array-elements value (second into)))
               ((and (vectorp into) (not (stringp into)))
                (let ((elements (objc::%array-elements value nil)))
                  (when (< (length into) (length elements))
                    (error "objc:invoke-into: the vector is shorter than the array's ~a elements"
                           (length elements)))
                  (dotimes (i (length elements) into)
                    (setf (aref into i) (aref elements i)))))
               (t value)))
        ((and (consp return-type) (eq (car return-type) :struct))
         (let ((kind (objc::%struct-kind return-type)))
           (cond ((and (member kind '(:ns-rect :ns-point :ns-size))
                       (vectorp into) (not (stringp into)))
                  (dotimes (i (length value) into)
                    (setf (aref into i) (aref value i))))
                 ((and (eq kind :ns-range) (consp into))
                  (setf (car into) (car value) (cdr into) (cdr value))
                  into)
                 (t value))))
        (t value)))

(defun objc:invoke-into (result class-or-object-pointer method &rest args)
  (objc::%invoke class-or-object-pointer method args
   (if (and (consp result) (eq (car result) :pointer)) :pointer result) nil))

;;; --- callbacks: what a method or a block defined in Lisp receives and answers ------

;; A declared type as the conversions read it: the parsed encoding, or :boolean for the
;; FLI types that convert to t and nil.
(defun objc::%declared-type (fli)
  (if (or (eq fli 'objc:objc-bool) (eq fli 'objc:objc-c++-bool)
          (eq fli :boolean) (and (consp fli) (eq (car fli) :boolean)))
      :boolean (car (objc::%parse-type (objc::%type-encoding fli) 0))))

;; The style symbols of an argument (string, array, (array style)), by name: string
;; may be read as cl:string or as objc's own.
(defun objc::%style-named-p (style name)
  (and style (symbolp style) (string= (symbol-name style) name)))

;; A raw argument the host handed a callback, as the Lisp value its SPEC -- (type
;; [style]) -- declares. An object argument arrives retained, which the pointer takes
;; over.
(defun objc::%convert-argument (spec raw)
  (let ((type (objc::%declared-type (first spec))) (style (second spec)))
    (cond ((eq type :boolean) (/= raw 0))
          ((eq type :object)
           (let ((pointer (objc::%wrap-object raw)))
             (cond ((null pointer) nil)
                   ((objc::%style-named-p style "STRING")
                    (objc:ns-string-to-string pointer))
                   ((objc::%style-named-p style "ARRAY")
                    (objc::%array-elements pointer nil))
                   ((and (consp style)
                         (objc::%style-named-p (car style) "ARRAY"))
                    (objc::%array-elements pointer (second style)))
                   (t pointer))))
          ((eq style :foreign) raw)
          ((and (consp (first spec)) (eq (car (first spec)) :pointer))
           (objc::%foreign-pointer raw (or (second (first spec)) :void)))
          (t (objc::%result type raw)))))

;; What a callback that failed answers.
(defun objc::%zero-answer (type)
  (cond ((eq type :void) nil)
        ((member type '(:float :double)) 0d0)
        ((and (consp type) (eq (car type) :struct))
         (let ((out nil))
           (dolist (leaf (objc::%leaves type) out)
             (push (if (member leaf '(:float :double)) 0d0 0) out))))
        (t 0)))

;; A callback's Lisp value answered as TYPE, raw as the host takes it back. NAME is the
;; callback's, for messages; the :foreign STYLE answers an object as the address it is.
(defun objc::%callback-answer (name type style value)
  (cond ((eq type :void) nil)
        ((eq type :boolean) (if (or (null value) (eql value 0)) 0 1))
        ((eq type :object)
         (cond ((null value) 0)
          ((eq style :foreign) (objc::%raw-address value name 0))
          ((stringp value)
           (objc::%pointer-address (objc:string-to-ns-string value)))
          ((and (vectorp value) (not (stringp value)))
           (let* ((temps (list nil)) (array (objc::%ns-array value temps)))
             (dolist (temp (car temps)) (objc:release temp))
             (objc::%pointer-address array)))
          ((objc::%as-pointer value)
           (objc::%pointer-address (objc::%as-pointer value)))
          ((objc::%block-address value))
          (t (error "~a: an object callback cannot answer ~s" name value))))
        ((eq type :cstring)
         (error "~a: a callback cannot answer a C string; answer an NSString"
                name))
        (t (objc::%raw-arg type value name 0 (list nil)))))

;;; --- C functions: fli:define-foreign-function -----------------------------------

;; (foreign-name argument-types result-type fixed) -> #(address types return-type
;; argument-types mode fixed), resolved on the first call.
(defvar objc::*foreign-functions* (make-hash-table :test 'equal))

;; Core Foundation's Create rule, which libdispatch follows too: a function whose name
;; says it creates or copies hands its caller the reference; any other answers one the
;; caller does not own.
(defun objc::%creates-p (name)
  (or (search "Create" name) (search "Copy" name) (search "_create" name)
      (search "_copy" name)))

(defun objc::%foreign-function (name arg-types result-type module fixed)
  (or
   (gethash (list name arg-types result-type fixed) objc::*foreign-functions*)
   (progn
     (when module (objc:ensure-objc-initialized :modules (list module)))
     (objc::%ready)
     (let ((address (objc::%symbol-address name))
           (encoding (make-string-output-stream))
           (i 0))
       (when (= address 0)
         (error "fli:define-foreign-function: no loaded image defines ~a" name))
       (write-string (objc::%type-encoding result-type) encoding)
       (dolist (fli arg-types)
         (write-string (if (and fixed (>= i fixed))
                           (objc::%promoted-encoding fli)
                           (objc::%type-encoding fli)) encoding)
         (setq i (+ i 1)))
       (let* ((raw (get-output-stream-string encoding))
              (parsed (objc::%parse-encoding raw))
              (return-type (first parsed)))
         (setf (gethash (list name arg-types result-type fixed)
                        objc::*foreign-functions*)
               (vector address (objc::%callable-types raw) return-type
                       (rest parsed)
                       (if (and (eq return-type :object)
                                (not (objc::%creates-p name)))
                           1
                           0) (if fixed fixed -1))))))))

;; A call of a function fli:define-foreign-function defined, on the calling thread.
(defun objc::%foreign-call (name arg-types result-type module fixed values)
  (let* ((function
          (objc::%foreign-function name arg-types result-type module fixed))
         (params (svref function 3))
         (temps (list nil)))
    (unless (= (length values) (length params))
      (error "~a takes ~a argument(s), got ~a" name (length params)
             (length values)))
    (let ((raw
           (unwind-protect (let ((raws nil) (i 0))
                             (dolist (value values)
                               (push
                                (objc::%raw-arg (car params) value name i temps)
                                raws)
                               (setq params (cdr params))
                               (setq i (+ i 1)))
                             (or (objc::%call-function (svref function 0)
                                                       (svref function 1)
                                                       (svref function 5)
                                                       (nreverse raws)
                                                       (svref function 4))
                                 (objc::%checked name :function)))
             (when (car temps)
               (dolist (temp (car temps)) (objc:release temp))))))
      (if (eq (objc::%declared-type result-type) :boolean)
          (/= raw 0)
          (objc::%result (svref function 2) raw)))))

;;; --- FLI: foreign objects ---------------------------------------------------------

;; LispWorks' FLI, the part the Objective-C manual's examples use: foreign objects
;; allocated in host memory (calloc / free through %call-function, so the same on every
;; target), read and written through %peek / %poke by their encodings. A structure's slots
;; are laid out by the C rule over its named slots (objc::*struct-slots*).

;; A pointer to ADDRESS of an FLI type the program names.
(defun objc::%foreign-pointer (address type)
  (objc::%make-foreign-pointer address type (objc::%type-encoding type)))

;; A pointer to ADDRESS whose pointee Objective-C described: its FLI type, and the
;; encoding it is read by -- the type's own when it has one, else the parsed PARSED.
(defun objc::%foreign-pointer-to (address type parsed)
  (objc::%make-foreign-pointer address type
   (or (objc::%encoding-or-nil type) (objc::%unparse parsed))))

(defun objc::%check-pointer (pointer who)
  (unless (fli:pointerp pointer)
    (error "~a: ~s is not a foreign pointer" who pointer))
  pointer)

(defun objc::%non-null (pointer who)
  (let ((address (fli::%pointer-address (objc::%check-pointer pointer who))))
    (when (= address 0) (error "~a: ~s is a null pointer" who pointer))
    address))

(defun objc::%boolean-type-p (fli)
  (or (eq fli 'objc:objc-bool) (eq fli 'objc:objc-c++-bool) (eq fli :boolean)
      (and (consp fli) (eq (car fli) :boolean))))

(defun fli:size-of (type-name) (car (objc::%fli-layout type-name)))

(defun fli:allocate-foreign-object
    (&key type pointer-type (nelems 1) initial-element initial-contents fill)
  (let ((element
         (or type
             (and (consp pointer-type) (eq (car pointer-type) :pointer)
                  (or (second pointer-type) :void))
             (error
              "fli:allocate-foreign-object: give a :type or a :pointer-type"))))
    (unless (and (integerp nelems) (> nelems 0))
      (error
       "fli:allocate-foreign-object: :nelems must be a positive integer, got ~s"
       nelems))
    (let* ((size (fli:size-of element))
           (pointer
            (objc::%foreign-pointer (objc::%calloc nelems (max size 1))
                                    element)))
      (when (and fill (/= fill 0))
        (objc::%write-octets (fli::%pointer-address pointer)
                             (make-array (* nelems size)
                                         :element-type '(unsigned-byte 8)
                                         :initial-element fill)))
      (cond (initial-contents
             (let ((i 0))
               (dolist (value (coerce initial-contents 'list))
                 (when (>= i nelems)
                   (error "fli:allocate-foreign-object: ~a initial elements for ~a element(s)"
                          (length initial-contents) nelems))
                 (setf (fli:dereference pointer :index i) value)
                 (setq i (+ i 1)))))
            (initial-element
             (dotimes (i nelems)
               (setf (fli:dereference pointer :index i) initial-element))))
      pointer)))

(defun fli:free-foreign-object (pointer)
  (objc::%check-pointer pointer "fli:free-foreign-object")
  (unless (= (fli::%pointer-address pointer) 0)
    (objc::%free (fli::%pointer-address pointer)))
  nil)

;; (values address type encoding) of element INDEX of what POINTER points at, read as TYPE
;; when one is given.
(defun objc::%element (pointer index type who)
  (let ((address (objc::%non-null pointer who)))
    (if type
        (values (+ address (* index (fli:size-of type))) type
                (objc::%type-encoding type))
        (values (+ address (* index (fli:size-of (fli::%pointer-type pointer))))
                (fli::%pointer-type pointer)
                (fli::%pointer-encoding pointer)))))

;; The value of TYPE at ADDRESS. An aggregate is not a Lisp value here: COPY nil answers a
;; pointer to it, t a copy in a fresh foreign object, :error (LispWorks' default) signals.
(defun objc::%load (address type encoding copy who)
  (let ((parsed (car (objc::%parse-type encoding 0))))
    (cond ((eq parsed :void)
           (error "~a: a pointer to :void has nothing to read; give a :type"
                  who))
          ((and (consp parsed) (member (car parsed) '(:struct :array :union)))
           (cond
            ((null copy) (objc::%make-foreign-pointer address type encoding))
            ((eq copy :error)
             (error "~a: ~s is an aggregate; pass :copy-foreign-object nil for a pointer to it or t for a copy"
                    who type))
            (t (let ((new (fli:allocate-foreign-object :type type))
                     (size (fli:size-of type)))
                 (objc::%write-octets (fli::%pointer-address new)
                                      (objc::%read-octets address size))
                 new))))
          ((objc::%boolean-type-p type) (/= (objc::%peek address encoding) 0))
          (t (objc::%result parsed (objc::%peek address encoding))))))

;; Writes VALUE as TYPE at ADDRESS: a foreign pointer's memory for an aggregate, else
;; what an argument of the type takes -- except what would not outlive the write (a Lisp
;; string or vector made into an Objective-C object for the call only).
(defun objc::%store (address type encoding value who)
  (let ((parsed (car (objc::%parse-type encoding 0))))
    (cond ((eq parsed :void)
           (error "~a: a pointer to :void has nothing to write; give a :type"
                  who))
          ((and (fli:pointerp value) (consp parsed)
                (member (car parsed) '(:struct :array :union)))
           (objc::%write-octets address
                                (objc::%read-octets (objc::%non-null value who)
                                                    (fli:size-of type))))
          ((and (member parsed '(:object :cstring))
                (or (stringp value) (vectorp value)))
           (error "~a: ~s would not outlive the write; store an object pointer"
                  who value))
          (t (objc::%poke address encoding
                          (objc::%raw-arg parsed value who 0 (list nil)))))
    value))

(defun fli:dereference
    (pointer &key (index 0) type (copy-foreign-object :error))
  (multiple-value-bind (address element encoding)
      (objc::%element pointer index type "fli:dereference")
    (objc::%load address element encoding copy-foreign-object
                 "fli:dereference")))

(defun (setf fli:dereference) (value pointer &key (index 0) type)
  (multiple-value-bind (address element encoding)
      (objc::%element pointer index type "(setf fli:dereference)")
    (objc::%store address element encoding value "(setf fli:dereference)")))

;; (values address type) of the slot SLOT-NAME -- a symbol, or a list naming a slot of a
;; slot -- of the structure POINTER points at (OBJECT-TYPE when given).
(defun objc::%slot (pointer slot-name object-type who)
  (let ((address (objc::%non-null pointer who))
        (type (or object-type (fli::%pointer-type pointer))))
    (dolist (name (if (listp slot-name) slot-name (list slot-name)))
      (let ((slots (objc::%struct-slots type)) (offset 0) (found nil))
        (unless slots
          (error "~a: ~s is not a structure type with named slots" who type))
        (dolist (slot slots)
          (unless found
            (let* ((layout (objc::%fli-layout (second slot))) (a (cdr layout)))
              (setq offset (* a (ceiling offset a)))
              (if (string= (string (first slot)) (string name))
                  (setq found slot)
                  (setq offset (+ offset (car layout)))))))
        (unless found
          (error "~a: ~s has no slot ~s; its slots are ~{~a~^, ~}" who type name
                 (mapcar #'first slots)))
        (setq address (+ address offset))
        (setq type (second found))))
    (values address type)))

(defun fli:foreign-slot-value
    (pointer slot-name &key type object-type (copy-foreign-object :error))
  (multiple-value-bind (address slot-type)
      (objc::%slot pointer slot-name object-type "fli:foreign-slot-value")
    (let ((read-as (or type slot-type)))
      (objc::%load address read-as (objc::%type-encoding read-as)
                   copy-foreign-object "fli:foreign-slot-value"))))

(defun (setf fli:foreign-slot-value)
    (value pointer slot-name &key type object-type)
  (multiple-value-bind (address slot-type) (objc::%slot pointer slot-name
                                            object-type
                                            "(setf fli:foreign-slot-value)")
    (let ((write-as (or type slot-type)))
      (objc::%store address write-as (objc::%type-encoding write-as) value
                    "(setf fli:foreign-slot-value)"))))

(defun fli:make-pointer (&key address type pointer-type)
  (unless (integerp address)
    (error "fli:make-pointer: :address must be an integer, got ~s" address))
  (objc::%foreign-pointer address
   (or type (and (consp pointer-type) (second pointer-type)) :void)))

(defun fli:pointer-address (pointer)
  (fli::%pointer-address (objc::%check-pointer pointer "fli:pointer-address")))

(defun fli:null-pointer-p (pointer)
  (= (fli::%pointer-address (objc::%check-pointer pointer "fli:null-pointer-p"))
     0))

(defun fli:pointer-eq (pointer1 pointer2)
  (= (fli::%pointer-address (objc::%check-pointer pointer1 "fli:pointer-eq"))
     (fli::%pointer-address (objc::%check-pointer pointer2 "fli:pointer-eq"))))

;;; --- the rest of OBJC -------------------------------------------------------------

(defun objc:ensure-objc-initialized (&key modules)
  (dolist (module modules)
    (unless (stringp module)
      (error "objc:ensure-objc-initialized: a module is a path string, got ~s"
             module))
    (objc::%load-module module))
  (objc::%initialize)
  (objc::%ready)
  nil)

(defun objc:alloc-init-object (class)
  (objc:invoke (objc:invoke class "alloc") "init"))

(defun objc:description (pointer)
  (objc:invoke-into 'string pointer "description"))

(defun objc:can-invoke-p (class-or-object-pointer method)
  (let ((sel (objc::%sel-address (objc:selector-name method))))
    (if (objc::%method-types (if (objc::%super-ref-p class-or-object-pointer)
                                 (objc::%super-class class-or-object-pointer)
                                 (objc::%lookup-class
                                  (objc::%target-address
                                   class-or-object-pointer))) sel)
        t
        nil)))

(defun objc:objc-class-method-signature (class-spec method-name)
  (let* ((cls
          (cond
           ((stringp class-spec)
            (objc::%pointer-address (objc:coerce-to-objc-class class-spec)))
           ((objc::%classp class-spec) (objc::%pointer-address class-spec))
           ((objc::%pointerp class-spec)
            (objc::%object-class (objc::%pointer-address class-spec)))
           (t
            (error
             "objc:objc-class-method-signature: ~s is not a class or an object"
             class-spec))))
         (sel (objc::%sel-address method-name))
         (raw
          (or (objc::%method-types cls sel)
              (objc::%method-types (objc::%object-class cls) sel))))
    (when raw
      (let ((parsed (objc::%parse-encoding raw)))
        (values (mapcar #'objc::%fli-type (rest parsed))
                (objc::%fli-type (first parsed)) raw)))))

(defun objc:trace-invoke (method)
  (setf (gethash method objc::*traced*) t)
  (setq objc::*tracing* t)
  method)

(defun objc:untrace-invoke (method)
  (remhash method objc::*traced*)
  (setq objc::*tracing* (> (hash-table-count objc::*traced*) 0))
  method)

(defun objc:objc-object-pointer (object-or-class)
  (or (objc::%as-pointer object-or-class)
      (error "objc:objc-object-pointer: ~s is not an Objective-C object"
             object-or-class)))

(defun objc:objectp (value) (if (objc::%pointerp value) t nil))

;;; --- bytes: objc:data and objc:bytes ---------------------------------------------

;; An NSMutableData holding a packed buffer's bytes -- exactly what write-sequence writes
;; for it (little-endian, row-major, the elements only) -- or a string's UTF-8. Mutable,
;; so one value serves a const void * parameter (bytes) and writable scratch
;; (mutableBytes). The layout is the primitive's (objc::%octets), since what a packed
;; buffer is differs per value representation.
(defun objc:data (value)
  (let ((octets (objc::%octets value)))
    (unless octets
      (error "objc:data expects a packed float array, a packed (unsigned-byte 8|16|32) vector or a string, got ~s"
             value))
    (let ((data
           (objc:invoke "NSMutableData" "dataWithLength:" (length octets))))
      (when (> (length octets) 0)
        (objc::%write-octets
         (fli:pointer-address (objc:invoke data "mutableBytes")) octets))
      data)))

;; An NSData's contents as a fresh (unsigned-byte 8) vector.
(defun objc:bytes (data)
  (let ((pointer (objc::%as-pointer data)))
    (unless (and pointer (not (objc::%classp pointer)))
      (error "objc:bytes expects an Objective-C object, got ~s" data))
    (let ((n (objc:invoke pointer "length")))
      (if (= n 0)
          (make-array 0 :element-type '(unsigned-byte 8))
          (objc::%read-octets
           (fli:pointer-address (objc:invoke pointer "bytes")) n)))))

;; The Lisp object made for a pointer: a standard-objc-object for an instance of a class
;; defined in Lisp, the Lisp class for such a class, and nil for anything else.
(defun objc:objc-object-from-pointer (pointer)
  (unless (objc::%pointerp pointer)
    (error "objc:objc-object-from-pointer: ~s is not an Objective-C object"
           pointer))
  (if objc::*pointer-object-hook*
      (funcall objc::*pointer-object-hook* pointer)
      nil))

;;; --- COCOA --------------------------------------------------------------------------

;; NSNotFound, NSIntegerMax on a 64-bit process.
(defconstant cocoa:ns-not-found 9223372036854775807)

;; A Foundation structure is the value invoke answers for it -- a vector for NSPoint,
;; NSSize and NSRect, a cons for NSRange -- or a foreign object holding one. These fill
;; either.
(defun objc::%fill (vector count values verb)
  (if (fli:pointerp vector)
      (objc::%fill-foreign vector values verb)
      (progn
        (unless (and (vectorp vector) (not (stringp vector))
                     (>= (length vector) count))
          (error "cocoa:~a: ~s is not a vector of ~a elements" verb vector
                 count))
        (let ((i 0))
          (dolist (value values)
            (unless (realp value)
              (error "cocoa:~a: ~s is not a real" verb value))
            (setf (aref vector i) value)
            (setq i (+ i 1))))
        vector)))

;; The fields of a Foundation structure written into the foreign object POINTER, a
;; structure of as many fields.
(defun objc::%fill-foreign (pointer values verb)
  (let* ((who (concatenate 'string "cocoa:" verb))
         (address (objc::%non-null pointer who))
         (parsed (car (objc::%parse-type (fli::%pointer-encoding pointer) 0))))
    (unless (and (consp parsed) (eq (car parsed) :struct)
                 (= (length (objc::%leaves parsed)) (length values)))
      (error "~a: ~s does not point at a structure of ~a fields" who pointer
             (length values)))
    (dolist (value values)
      (unless (realp value) (error "~a: ~s is not a real" who value)))
    (objc::%poke address (objc::%unparse parsed)
                 (mapcar (lambda (leaf value)
                           (if (member leaf '(:float :double))
                               (float value 1d0)
                               (objc::%bits64 value who 0)))
                         (objc::%leaves parsed) values))
    pointer))

(defun cocoa:set-ns-point* (point x y)
  (objc::%fill point 2 (list x y) "set-ns-point*"))

(defun cocoa:set-ns-size* (size width height)
  (objc::%fill size 2 (list width height) "set-ns-size*"))

(defun cocoa:set-ns-rect* (rect x y width height)
  (objc::%fill rect 4 (list x y width height) "set-ns-rect*"))

(defun cocoa:set-ns-range* (range location length)
  (unless (and (integerp location) (integerp length))
    (error
     "cocoa:set-ns-range*: the location and length are integers, got ~s and ~s"
     location length))
  (cond ((fli:pointerp range)
         (objc::%fill-foreign range (list location length) "set-ns-range*"))
        ((consp range)
         (setf (car range) location (cdr range) length)
         range)
        (t (error "cocoa:set-ns-range*: ~s is not a cons or a foreign pointer"
                  range))))

;; The default notification center's observers: TARGET receives SELECTOR (a method taking
;; the NSNotification) for notifications of NAME from OBJECT; nil matches any.
(defun cocoa:add-observer (target selector &key name object center)
  (objc:invoke (or center (objc:invoke "NSNotificationCenter" "defaultCenter"))
               "addObserver:selector:name:object:" target
               (objc:coerce-to-selector selector) name object)
  nil)

(defun cocoa:remove-observer (target &key name object center)
  (objc:invoke (or center (objc:invoke "NSNotificationCenter" "defaultCenter"))
               "removeObserver:name:object:" target name object)
  nil)
