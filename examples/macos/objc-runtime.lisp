;;;; objc-runtime.lisp -- the Objective-C runtime itself, from Lisp: the half of the
;;;; built-in `objc` package that has nothing to do with windows.
;;;;
;;;; Objective-C settles everything at the moment it happens. A selector is a name looked
;;;; up in a table at the call, a class is an object you can interrogate, a method carries
;;;; its own type declaration, a value is reached by a string key, and a class can be born
;;;; after the program started. That is the deal a Lisp already makes, so the two meet
;;;; with no glue in between: this program passes selectors around as strings, walks class
;;;; hierarchies it did not know, reads a method's type encoding out of the runtime, sends
;;;; the one family of selectors that encoding does not describe, reaches values by key,
;;;; and hands Foundation a class whose methods are written in Lisp -- which Foundation
;;;; then calls, from inside its own collection code.
;;;;
;;;; No window, no nib, no header file: it prints to the terminal and ends by itself. Its
;;;; companion `counter.lisp` is the other half of the package, the AppKit one. macOS
;;;; only, on the interpreter, compiled to a JVM class or jar, and as a --native
;;;; executable on Apple silicon; never as WASM.
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/objc-runtime.lisp
;;;;   ./target/rontolisp examples/macos/objc-runtime.lisp
;;;;   ./target/rontolisp examples/macos/objc-runtime.lisp -o ObjcRuntime.class --class-name ObjcRuntime && java ObjcRuntime
;;;;   ./target/rontolisp examples/macos/objc-runtime.lisp -o objc-runtime.jar && java -jar objc-runtime.jar
;;;;   ./target/rontolisp examples/macos/objc-runtime.lisp --native -o objc-runtime && ./objc-runtime

;;; Every Objective-C answer that is text is an NSString, and every object can describe
;;; itself -- two helpers, and the rest of the file needs no others. A Lisp string
;;; handed to a method that takes an object becomes an NSString on the way in; an
;;; NSString coming back is an object, and `objc:invoke-into 'string` reads it out.

(defun joined (array)
  (objc:invoke-into 'string array "componentsJoinedByString:" ", "))

;; An answer is an integer, a float, a vector or a cons (a struct) or another object;
;; only the last needs unwrapping, and `objc:objectp` is how a program tells them apart.
(defun show (value) (if (objc:objectp value) (objc:description value) value))

;;; 1. A selector is a string, and the receiver decides at the call
;;;
;;; Nothing here knows what these objects are. It asks each one whether it answers to a
;;; name -- `respondsToSelector:`, the question Objective-C asks instead of declaring a
;;; type -- and sends only what comes back true. Four names, four unrelated receivers, no
;;; common superclass below NSObject and no interface anywhere. The answer is a BOOL,
;;; which `objc:invoke` returns as 1 or 0 (both true in Lisp); `objc:invoke-bool` is the
;;; send that answers t or nil.

(format t "== 1. a selector is a string, resolved by the receiver ==~%")

(defvar *selectors* '("length" "count" "doubleValue" "uppercaseString"))

(defvar *dictionary* (objc:invoke "NSMutableDictionary" "dictionary"))
(objc:invoke *dictionary* "setValue:forKey:" "Brad Cox" "author")

(defvar *receivers*
  (list (cons "NSString" (objc:string-to-ns-string "Objective-C"))
        (cons "NSNumber" (objc:invoke "NSNumber" "numberWithDouble:" 2.5))
        (cons "NSArray" (objc:invoke "NSArray" "arrayWithObject:" "only"))
        (cons "NSDictionary" *dictionary*)))

(dolist (receiver *receivers*)
  (format t "~14a" (car receiver))
  (dolist (selector *selectors*)
    (when (objc:invoke-bool (cdr receiver) "respondsToSelector:" selector)
      (format t "  ~a=~a" selector
              (show (objc:invoke (cdr receiver) selector)))))
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
      (cons (objc:objc-class-name class)
            (class-chain (objc:invoke class "superclass")))))

(defun show-chain (label object)
  (format t "~24a ~{~a~^ <- ~}~%" label
          (class-chain (objc:invoke object "class"))))

(show-chain "a short NSString" (objc:string-to-ns-string "hi"))
(show-chain "two strings appended"
            (objc:invoke (objc:string-to-ns-string "0123456789")
                         "stringByAppendingString:"
                         "0123456789012345678901234567890"))
(show-chain "an NSNumber" (objc:invoke "NSNumber" "numberWithDouble:" 2.5))
(show-chain "an empty NSArray" (objc:invoke "NSMutableArray" "array"))

;;; 3. A method carries its own declaration
;;;
;;; `method_getTypeEncoding` describes every method completely, which is why `objc:invoke`
;;; never guesses a signature: it parses that encoding and marshals each argument by it.
;;; The same declaration is readable from Lisp through NSMethodSignature, so a program can
;;; find out what a method wants before sending it anything. Argument 0 is the receiver
;;; (`@`) and argument 1 the selector (`:`); the rest are the method's own. A `char *`
;;; answer is already a Lisp string.

(format t "~%== 3. a method describes itself ==~%")

(defun argument-types (signature)
  (let ((types nil))
    (dotimes (i (objc:invoke signature "numberOfArguments"))
      (push (objc:invoke signature "getArgumentTypeAtIndex:" i) types))
    (reverse types)))

(defun show-signature (label object selector)
  (let ((signature (objc:invoke object "methodSignatureForSelector:" selector)))
    (format t "~28a returns ~a, takes ~{~a~^ ~}~%"
            (format nil "-[~a ~a]" label selector)
            (objc:invoke signature "methodReturnType")
            (argument-types signature))))

(show-signature "NSString" (objc:string-to-ns-string "x") "rangeOfString:")
(show-signature "NSString" (objc:string-to-ns-string "x") "hasPrefix:")
(show-signature "NSArray" (objc:invoke "NSMutableArray" "array")
                "objectAtIndex:")

;; `{_NSRange=QQ}` above is why this answers a location and a length, and not an
;; address: an NSRange comes back as the cons (location . length).
(format t "~28a ~a~%" "so rangeOfString: answers"
 (objc:invoke (objc:string-to-ns-string "Objective-C") "rangeOfString:" "C"))

;;; 4. A declaration that is not the whole call
;;;
;;; Every method describes itself -- except a variadic one, which is declared exactly like
;;; its fixed-arity twin. `+[NSArray arrayWithObjects:]` and `+[NSArray arrayWithObject:]`
;;; are both `@@:@`, and no encoding anywhere says which is which; on Apple silicon that
;;; difference is the whole call, since a variadic argument travels on the stack where a
;;; fixed one travels in a register. So `objc:invoke` knows the family by NAME: the
;;; nil-terminated constructors and the format-string one take as many arguments as you
;;; give them past the declared arity, and the nil terminator is the binding's own.

(format t "~%== 4. a variadic selector takes the whole list ==~%")

(defvar *dialects*
  (objc:invoke "NSArray" "arrayWithObjects:" "Lisp" "Smalltalk" "Objective-C"))

(format t "~28a ~a~%" "arrayWithObjects: built" (joined *dialects*))

;; The same declaration as arrayWithObject:, and one argument is still a one-element list
;; rather than a dead process.
(defvar *one* (objc:invoke "NSArray" "arrayWithObjects:" "only"))

(format t "~28a ~a~%" "one argument, one element" (objc:invoke *one* "count"))

;; A format argument's carrier is picked from the VALUE, which is what %@, %ld and %f read
;; back out of the argument list.
(format t "~28a ~a~%" "stringWithFormat: answers"
        (objc:invoke-into 'string "NSString" "stringWithFormat:"
                          "%@ has %ld entries, %.1f%% of the deck" "the hand"
                          (objc:invoke *dialects* "count") 5.75))

;;; 5. A value is reached by a string key
;;;
;;; Key-value coding resolves an accessor by name at run time, and over a collection the
;;; key means more than one lookup: `valueForKey:` maps it across the elements, a key path
;;; with an operator folds them, and a sort descriptor orders them by a key the program
;;; only ever holds as text.

(format t "~%== 5. a value is a string key ==~%")

(defvar *languages* (objc:invoke "NSMutableArray" "array"))

(dolist (name '("Objective-C" "Lisp" "Smalltalk"))
  (objc:invoke *languages* "addObject:" name))

(format t "~24a ~a~%" "the array" (joined *languages*))
(format t "~24a ~a~%" "valueForKey: \"length\""
        (joined (objc:invoke *languages* "valueForKey:" "length")))
(format t "~24a ~a~%" "keyPath \"@max.length\""
        (show (objc:invoke *languages* "valueForKeyPath:" "@max.length")))
;; A Lisp vector passed where an object goes becomes an NSArray.
(format t "~24a ~a~%" "sorted by \"length\""
        (joined
         (objc:invoke *languages* "sortedArrayUsingDescriptors:"
                      (vector
                       (objc:invoke "NSSortDescriptor"
                                    "sortDescriptorWithKey:ascending:" "length"
                                    t)))))

;; A dictionary declares no accessors at all, and answers the same two messages.
(objc:invoke *dictionary* "setValue:forKey:"
             (objc:invoke "NSNumber" "numberWithDouble:" 1984.0) "year")
(format t "~24a ~a, ~a~%" "the dictionary by key"
        (show (objc:invoke *dictionary* "valueForKey:" "author"))
        (show (objc:invoke *dictionary* "valueForKey:" "year")))

;;; 6. A class defined at run time, whose methods are written in Lisp
;;;
;;; `objc:define-objc-class` defines a Lisp class and registers a real Objective-C class
;;; for it with the runtime; `objc:define-objc-method` gives that class a method whose
;;; body is Lisp and whose receiver is the Lisp instance. The card's rank is an ordinary
;;; slot of that instance, and `objc:objc-object-from-pointer` maps the object Foundation
;;; hands the method back to its Lisp instance.
;;;
;;; The direction of the call is the point: nothing below sends `isEqual:`. NSArray does,
;;; from inside `containsObject:` and `indexOfObject:`, to an object whose answer is
;;; computed in Lisp.

(format t "~%== 6. Foundation calls back into Lisp ==~%")

(objc:define-objc-class card ()
  ((rank :initarg :rank :reader card-rank))
  (:objc-class-name "LispCard"))

(defun rank-of (pointer)
  (let ((object (objc:objc-object-from-pointer pointer)))
    (if (typep object 'card) (card-rank object) nil)))

(objc:define-objc-method ("isEqual:" :boolean)
  ((self card) (other objc:objc-object-pointer))
  (format t "   NSArray asked isEqual: ~a vs ~a~%" (card-rank self)
          (rank-of other))
  (equal (card-rank self) (rank-of other)))

(defun card (rank) (make-instance 'card :rank rank))

(defvar *hand* (objc:invoke "NSMutableArray" "array"))
(objc:invoke *hand* "addObject:" (card "ace"))
(objc:invoke *hand* "addObject:" (card "seven"))

(format t "containsObject: a seven -> ~a~%"
        (objc:invoke-bool *hand* "containsObject:" (card "seven")))
(format t "indexOfObject: a seven -> ~a~%"
        (objc:invoke *hand* "indexOfObject:" (card "seven")))

;;; 7. Delivered by name, to a receiver the sender never sees
;;;
;;; The notification centre is the runtime's habit taken to its end: the poster names a
;;; string, the observer names a string and a selector, and neither knows the other's
;;; type. Here the observer is an instance of a class that did not exist a moment ago, and
;;; the selector runs a method written in Lisp.

(format t "~%== 7. a notification, observed by Lisp ==~%")

(objc:define-objc-class observer ()
  ()
  (:objc-class-name "LispObserver"))

(objc:define-objc-method ("noteArrived:" :void)
  ((self observer) (note objc:objc-object-pointer))
  (format t "observed ~a carrying ~a~%" (objc:invoke-into 'string note "name")
          (show (objc:invoke note "object"))))

(defvar *observer* (make-instance 'observer))
(defvar *centre* (objc:invoke "NSNotificationCenter" "defaultCenter"))

(objc:invoke *centre* "addObserver:selector:name:object:" *observer*
             "noteArrived:" "rontolisp.card.played" nil)
(objc:invoke *centre* "postNotificationName:object:" "rontolisp.card.played"
             "the ace")

(objc:invoke *centre* "removeObserver:" *observer*)
(objc:invoke *centre* "postNotificationName:object:" "rontolisp.card.played"
             "the seven")
(format t "after removeObserver:, the same post above printed nothing~%")

(format t "~%no window was opened, and nothing above declared a type~%")
