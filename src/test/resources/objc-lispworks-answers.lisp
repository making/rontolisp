;; What LispWorks 8.1 answers where its Objective-C and Cocoa manual is silent,
;; self-contradictory or stale -- recorded, not derived from the manual. ObjcBaseTest
;; asserts the new objc base against every entry (the interpreter; the JVM class and
;; the --native executable print what the interpreter prints for the same probes, in
;; objc-base-corpus.lisp).
;;
;; Recorded from LispWorks Personal 8.1.2 on arm64 (Apple silicon, macOS 15 / Darwin 25)
;; by pasting the probe forms into the IDE Listener by hand: the Personal edition cannot
;; be scripted (it ignores -eval, runs no initialization file, and blocks SAVE-IMAGE and
;; LOAD-ALL-PATCHES). Regenerating is deliberate and rare.
;;
;; Each entry is (key probe answer). The probe is what was typed after
;;   (setq s (objc:invoke "NSString" "stringWithUTF8String:" "hello world"))
;;   (setq c (objc:coerce-to-objc-class "NSString"))
;; and the answer what the Listener printed, or for :missing-method-report the report
;; of the condition, whose address varies from run to run.

(
 ;; On Apple silicon BOOL encodes as 'B' (C99 _Bool), yet invoke answers 1 or 0 for it,
 ;; as the reference page says; only invoke-bool answers T / NIL.
 (:bool-invoke-true (objc:invoke s "isKindOfClass:" c) 1)
 (:bool-invoke-false (objc:invoke s "hasPrefix:" "zzz") 0)
 (:bool-invoke-bool (objc:invoke-bool s "isKindOfClass:" c) t)
 (:length (objc:invoke s "length") 11)
 ;; A bare invoke converts an NSRange result to a cons.
 (:range-of-string (objc:invoke s "rangeOfString:" "world") (6 . 5))
 ;; The reference pages give ns-point / ns-size :float slots and ns-range
 ;; (:unsigned :int) ones; the implementation uses doubles and 64-bit integers.
 (:sizeof-ns-point (fli:size-of 'cocoa:ns-point) 16)
 (:sizeof-ns-size (fli:size-of 'cocoa:ns-size) 16)
 (:sizeof-ns-rect (fli:size-of 'cocoa:ns-rect) 32)
 (:sizeof-ns-range (fli:size-of 'cocoa:ns-range) 16)
 ;; Three values; the argument types always start (objc-object-pointer sel), and a
 ;; Foundation structure is named (:struct cocoa:...).
 (:sig-length (multiple-value-list (objc:objc-class-method-signature "NSString" "length"))
  ((objc:objc-object-pointer objc:sel) (:unsigned :long-long) "Q16@0:8"))
 (:sig-range-of-string
  (multiple-value-list (objc:objc-class-method-signature "NSString" "rangeOfString:"))
  ((objc:objc-object-pointer objc:sel objc:objc-object-pointer) (:struct cocoa:ns-range)
   "{_NSRange=QQ}24@0:8@16"))
 (:sig-substring-with-range
  (multiple-value-list (objc:objc-class-method-signature "NSString" "substringWithRange:"))
  ((objc:objc-object-pointer objc:sel (:struct cocoa:ns-range)) objc:objc-object-pointer
   "@32@0:8{_NSRange=QQ}16"))
 ;; A class name and a selector that is both a class and an instance method: the
 ;; INSTANCE method's signature.
 (:sig-description
  (multiple-value-list (objc:objc-class-method-signature "NSObject" "description"))
  ((objc:objc-object-pointer objc:sel) objc:objc-object-pointer "@16@0:8"))
 (:can-invoke-p-bogus (objc:can-invoke-p s "noSuchMethodAtAll") nil)
 ;; selector-name passes a string through, unregistered.
 (:selector-name-string (objc:selector-name "notAColonName") "notAColonName")
 (:selector-name-sel (objc:selector-name (objc:coerce-to-selector "setWidth:height:"))
  "setWidth:height:")
 (:retain-count-new (objc:retain-count (objc:invoke "NSObject" "new")) 1)
 ;; A missing method is a SIMPLE-ERROR signalled before any message is sent, naming the
 ;; RUNTIME class (object_getClass), not the class the caller named.
 (:missing-method-condition
  (handler-case (objc:invoke s "noSuchMethodAtAll") (error (e) (type-of e)))
  simple-error)
 (:missing-method-report
  (handler-case (objc:invoke s "noSuchMethodAtAll") (error (e) (princ-to-string e)))
  "No method \"noSuchMethodAtAll\" for object #<Pointer: OBJC:OBJC-OBJECT-POINTER = #x00000008679CF880>, class \"__NSCFString\"."))
