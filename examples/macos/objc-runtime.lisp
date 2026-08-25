;;;; objc-runtime.lisp -- the Objective-C runtime itself, from Lisp: the half of the
;;;; built-in `objc` package that has nothing to do with windows.
;;;;
;;;; Objective-C settles everything at the moment it happens. A selector is a name looked
;;;; up in a table at the call, a class is an object you can interrogate, a method carries
;;;; its own type declaration, a value is reached by a string key, and a class can be born
;;;; after the program started. That is the deal a Lisp already makes, so the two meet
;;;; with no glue in between: this program passes selectors around as strings, walks class
;;;; hierarchies it did not know, reads a method's type encoding out of the runtime,
;;;; reaches values by key, and hands Foundation a class whose methods are Lisp closures
;;;; -- which Foundation then calls, from inside its own collection code.
;;;;
;;;; No window, no nib, no header file: it prints to the terminal and ends by itself. Its
;;;; companion `counter.lisp` is the other half of the package, the AppKit one. macOS
;;;; only, on the interpreter and compiled to a JVM class or jar; never as WASM.
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/objc-runtime.lisp
;;;;   ./target/rontolisp examples/macos/objc-runtime.lisp
;;;;   ./target/rontolisp examples/macos/objc-runtime.lisp -o ObjcRuntime.class --class-name ObjcRuntime && java ObjcRuntime
;;;;   ./target/rontolisp examples/macos/objc-runtime.lisp -o objc-runtime.jar && java -jar objc-runtime.jar

;;; Every Objective-C answer that is text is an NSString, and every object can describe
;;; itself -- three helpers, and the rest of the file needs no others.

(defun utf8 (nsstring) (objc:send nsstring "UTF8String"))

(defun joined (array)
  (utf8 (objc:send array "componentsJoinedByString:" (objc:string ", "))))

;; An answer is an integer, a float, a list (a struct) or another object; only the last
;; needs unwrapping, and `objc:objectp` is how a program tells them apart.
(defun show (value)
  (if (objc:objectp value) (utf8 (objc:send value "description")) value))

;;; 1. A selector is a string, and the receiver decides at the call
;;;
;;; Nothing here knows what these objects are. It asks each one whether it answers to a
;;; name -- `respondsToSelector:`, the question Objective-C asks instead of declaring a
;;; type -- and sends only what comes back true. Four names, four unrelated receivers, no
;;; common superclass below NSObject and no interface anywhere.

(format t "== 1. a selector is a string, resolved by the receiver ==~%")

(defvar *selectors* '("length" "count" "doubleValue" "uppercaseString"))

(defvar *dictionary* (objc:send "NSMutableDictionary" "dictionary"))
(objc:send *dictionary* "setValue:forKey:" (objc:string "Brad Cox") "author")

(defvar *receivers*
  (list (cons "NSString" (objc:string "Objective-C"))
        (cons "NSNumber" (objc:send "NSNumber" "numberWithDouble:" 2.5))
        (cons "NSArray"
              (objc:send "NSArray" "arrayWithObject:" (objc:string "only")))
        (cons "NSDictionary" *dictionary*)))

(dolist (receiver *receivers*)
  (format t "~14a" (car receiver))
  (dolist (selector *selectors*)
    (when (objc:send (cdr receiver) "respondsToSelector:" selector)
      (format t "  ~a=~a" selector (show (objc:send (cdr receiver) selector)))))
  (format t "~%"))

;;; 2. A class is an object, and the one you asked for is rarely the one you get
;;;
;;; NSString and NSArray are class clusters: the initialiser answers a private subclass
;;; chosen for the value, and the only way to learn which is to ask the object at run
;;; time. Classes are objects too, so walking up the hierarchy is the same message send
;;; as everything else.

(format t "~%== 2. the class hierarchy, walked at run time ==~%")

(defun class-chain (class)
  (if (null class)
      nil
      (cons (show class) (class-chain (objc:send class "superclass")))))

(defun show-chain (label object)
  (format t "~24a ~{~a~^ <- ~}~%" label
          (class-chain (objc:send object "class"))))

(show-chain "(objc:string \"hi\")" (objc:string "hi"))
(show-chain "two strings appended"
            (objc:send (objc:string "0123456789") "stringByAppendingString:"
                       (objc:string "0123456789012345678901234567890")))
(show-chain "an NSNumber" (objc:send "NSNumber" "numberWithDouble:" 2.5))
(show-chain "an empty NSArray" (objc:send "NSMutableArray" "array"))

;;; 3. A method carries its own declaration
;;;
;;; `method_getTypeEncoding` describes every method completely, which is why `objc:send`
;;; never guesses a signature: it parses that encoding and marshals each argument by it.
;;; The same declaration is readable from Lisp through NSMethodSignature, so a program can
;;; find out what a method wants before sending it anything. Argument 0 is the receiver
;;; (`@`) and argument 1 the selector (`:`); the rest are the method's own.

(format t "~%== 3. a method describes itself ==~%")

(defun argument-types (signature)
  (let ((types nil))
    (dotimes (i (objc:send signature "numberOfArguments"))
      (push (objc:send signature "getArgumentTypeAtIndex:" i) types))
    (reverse types)))

(defun show-signature (label object selector)
  (let ((signature (objc:send object "methodSignatureForSelector:" selector)))
    (format t "~28a returns ~a, takes ~{~a~^ ~}~%"
            (format nil "-[~a ~a]" label selector)
            (objc:send signature "methodReturnType")
            (argument-types signature))))

(show-signature "NSString" (objc:string "x") "rangeOfString:")
(show-signature "NSString" (objc:string "x") "hasPrefix:")
(show-signature "NSArray" (objc:send "NSMutableArray" "array") "objectAtIndex:")

;; `{_NSRange=QQ}` above is why this answers a location and a length, and not an address.
(format t "~28a ~a~%" "so rangeOfString: answers"
        (objc:send (objc:string "Objective-C") "rangeOfString:" "C"))

;;; 4. A value is reached by a string key
;;;
;;; Key-value coding resolves an accessor by name at run time, and over a collection the
;;; key means more than one lookup: `valueForKey:` maps it across the elements, a key path
;;; with an operator folds them, and a sort descriptor orders them by a key the program
;;; only ever holds as text.

(format t "~%== 4. a value is a string key ==~%")

(defvar *languages* (objc:send "NSMutableArray" "array"))

(dolist (name '("Objective-C" "Lisp" "Smalltalk"))
  (objc:send *languages* "addObject:" (objc:string name)))

(format t "~24a ~a~%" "the array" (joined *languages*))
(format t "~24a ~a~%" "valueForKey: \"length\""
        (joined (objc:send *languages* "valueForKey:" "length")))
(format t "~24a ~a~%" "keyPath \"@max.length\""
        (show (objc:send *languages* "valueForKeyPath:" "@max.length")))
(format t "~24a ~a~%" "sorted by \"length\""
        (joined
         (objc:send *languages* "sortedArrayUsingDescriptors:"
                    (objc:send "NSArray" "arrayWithObject:"
                               (objc:send "NSSortDescriptor"
                                          "sortDescriptorWithKey:ascending:"
                                          "length" t)))))

;; A dictionary declares no accessors at all, and answers the same two messages.
(objc:send *dictionary* "setValue:forKey:"
           (objc:send "NSNumber" "numberWithDouble:" 1984.0) "year")
(format t "~24a ~a, ~a~%" "the dictionary by key"
        (show (objc:send *dictionary* "valueForKey:" "author"))
        (show (objc:send *dictionary* "valueForKey:" "year")))

;;; 5. A class defined at run time, whose methods are Lisp closures
;;;
;;; `objc:define-class` registers a real class with the runtime; its methods are Lisp
;;; functions that receive the receiver first. It declares no instance variables, so the
;;; state lives on the Lisp side in a table keyed by the object's address -- which is all
;;; an instance variable ever was.
;;;
;;; The direction of the call is the point: nothing below sends `isEqual:`. NSArray does,
;;; from inside `containsObject:` and `indexOfObject:`, to an object whose answer is a
;;; closure written in Lisp.

(format t "~%== 5. Foundation calls back into Lisp ==~%")

(defvar *ranks* (make-hash-table))

(defvar *card-class*
  (objc:define-class "LispCard"
    "NSObject"
    (list
     (list "isEqual:"
           (lambda (self other)
             (format t "   NSArray asked isEqual: ~a vs ~a~%"
                     (gethash (objc:address self) *ranks*)
                     (gethash (objc:address other) *ranks*))
             (equal (gethash (objc:address self) *ranks*)
                    (gethash (objc:address other) *ranks*)))))))

(defun card (rank)
  (let ((object (objc:send (objc:send *card-class* "alloc") "init")))
    (setf (gethash (objc:address object) *ranks*) rank)
    object))

(defvar *hand* (objc:send "NSMutableArray" "array"))
(objc:send *hand* "addObject:" (card "ace"))
(objc:send *hand* "addObject:" (card "seven"))

(format t "containsObject: a seven -> ~a~%"
        (objc:send *hand* "containsObject:" (card "seven")))
(format t "indexOfObject: a seven -> ~a~%"
        (objc:send *hand* "indexOfObject:" (card "seven")))

;;; 6. Delivered by name, to a receiver the sender never sees
;;;
;;; The notification centre is the runtime's habit taken to its end: the poster names a
;;; string, the observer names a string and a selector, and neither knows the other's
;;; type. Here the observer is an instance of a class that did not exist a moment ago, and
;;; the selector runs a closure.

(format t "~%== 6. a notification, observed by a Lisp closure ==~%")

(defvar *observer-class*
  (objc:define-class "LispObserver"
    "NSObject"
    (list
     (list "noteArrived:"
           (lambda (self note)
             (format t "observed ~a carrying ~a~%"
                     (utf8 (objc:send note "name"))
                     (show (objc:send note "object"))))))))

(defvar *observer* (objc:send (objc:send *observer-class* "alloc") "init"))
(defvar *centre* (objc:send "NSNotificationCenter" "defaultCenter"))

(objc:send *centre* "addObserver:selector:name:object:" *observer*
           "noteArrived:" "rontolisp.card.played" nil)
(objc:send *centre* "postNotificationName:object:" "rontolisp.card.played"
           (objc:string "the ace"))

(objc:send *centre* "removeObserver:" *observer*)
(objc:send *centre* "postNotificationName:object:" "rontolisp.card.played"
           (objc:string "the seven"))
(format t "after removeObserver:, the same post above printed nothing~%")

(format t "~%no window was opened, and nothing above declared a type~%")
