;;;; system-frameworks.lisp -- macOS itself as a Lisp library: no dependency to
;;;; install, no window to open.
;;;;
;;;; The built-in `objc` package binds the Objective-C runtime, and every framework on
;;;; the machine speaks it. A framework that is not linked into this process is one
;;;; NSBundle message away, so the surface is not AppKit but the whole system: text
;;;; recognition, natural language, Core Image, speech. Nothing below is a wrapper
;;;; someone had to write in Java first -- a class is a string, a selector is a string,
;;;; and the runtime types the call from its own encoding.
;;;;
;;;; The centre of the file is section 6: a Lisp string is drawn into an image by Core
;;;; Image and read back out of it by Vision, and the two strings are compared. The
;;;; round trip leaves the process only to reach the frameworks.
;;;;
;;;; It prints to the terminal and ends by itself. macOS only, on the interpreter,
;;;; compiled to a JVM class or jar, and as a --native executable on Apple silicon;
;;;; never as WASM.
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/system-frameworks.lisp
;;;;   ./target/rontolisp examples/macos/system-frameworks.lisp
;;;;   ./target/rontolisp examples/macos/system-frameworks.lisp -o SystemFrameworks.class \
;;;;     --class-name SystemFrameworks && java SystemFrameworks
;;;;   ./target/rontolisp examples/macos/system-frameworks.lisp -o system-frameworks.jar && \
;;;;     java -jar system-frameworks.jar
;;;;   ./target/rontolisp examples/macos/system-frameworks.lisp --native -o system-frameworks && \
;;;;     ./system-frameworks

;;; A Lisp string passed where a method takes an object becomes an NSString, so no
;;; call below converts one by hand; an NSString that comes back is an object, and
;;; `objc:invoke-into 'string` is the send that answers it as Lisp text.

;;; 1. A dependency is a message
;;;
;;; Vision, NaturalLanguage and Core Image are not linked into this process: no Lisp
;;; program links anything. `load` maps the framework and registers its classes with the
;;; runtime, and from the next line on `(objc:invoke "VNRecognizeTextRequest" ...)` names
;;; a class that did not exist a moment ago. This is the whole of dependency management
;;; here -- there is no manifest, no classpath and no download.

(format t "== 1. a dependency is a message ==~%")

(defun load-framework (name)
  (let ((bundle
         (objc:invoke "NSBundle" "bundleWithPath:"
          (format nil "/System/Library/Frameworks/~a.framework" name))))
    (if (and bundle (objc:invoke-bool bundle "load")) t nil)))

(dolist (name '("Vision" "NaturalLanguage" "CoreImage"))
  (format t "~16a loaded=~a~%" name (load-framework name)))

;;; 2. Which language is this?
;;;
;;; NLLanguageRecognizer is a model, shipped with the system and already on disk. The
;;; call is one message and the answer is a BCP-47 tag.

(format t "~%== 2. the language of a string ==~%")

(defun dominant-language (text)
  (objc:invoke-into 'string "NLLanguageRecognizer" "dominantLanguageForString:"
                    text))

(dolist (text
         '("これは日本語の文章です" "this sentence is in english"
           "cette phrase est en français"))
  (format t "~a -> ~a~%" (dominant-language text) text))

;;; 3. Spelling, from the same checker the text fields use
;;;
;;; `checkSpellingOfString:startingAt:` answers an NSRange -- a C struct, which the
;;; binding answers as the cons (location . length), because the selector's own type
;;; encoding says so and nothing here declared it. The same cons goes back in as an
;;; NSRange argument.

(format t "~%== 3. spelling ==~%")

(defvar *checker* (objc:invoke "NSSpellChecker" "sharedSpellChecker"))

(defun misspelling (text)
  (objc:invoke *checker* "checkSpellingOfString:startingAt:" text 0))

(defun guesses (text range)
  (let ((array
         (objc:invoke *checker*
          "guessesForWordRange:inString:language:inSpellDocumentWithTag:" range
          text "en" 0)))
    (objc:invoke-into 'string array "componentsJoinedByString:" ", ")))

(defvar *typo* "i recieve mail")
(defvar *range* (misspelling *typo*))

(format t "~s: first misspelling at ~a~%" *typo* *range*)
(format t "guesses: ~a~%" (guesses *typo* *range*))

;;; 4. Structure pulled out of prose
;;;
;;; NSDataDetector is the machinery behind the blue underlines in Mail. The mask is a
;;; plain integer, the matches are an NSArray, and each match carries the range it
;;; found -- so a Lisp program gets dates, links and phone numbers out of free text
;;; with no regular expression of its own. The constructor reports a bad mask through
;;; an NSError, which `objc:invoke-with-error` turns into an objc:ns-error condition.

(format t "~%== 4. dates, links and numbers in free text ==~%")

(defvar *checking-types*
  (list (cons 8 "date") (cons 16 "address") (cons 32 "link")
        (cons 2048 "phone")))

(defun detector ()
  (objc:invoke-with-error "NSDataDetector" "dataDetectorWithTypes:error:"
                          (reduce #'+ (mapcar #'car *checking-types*))))

(defun detect (text)
  (let* ((string (objc:string-to-ns-string text))
         (matches
          (objc:invoke (detector) "matchesInString:options:range:" string 0
                       (cons 0 (objc:invoke string "length")))))
    (dotimes (i (objc:invoke matches "count"))
      (let* ((match (objc:invoke matches "objectAtIndex:" i))
             (range (objc:invoke match "range"))
             (kind
              (cdr (assoc (objc:invoke match "resultType") *checking-types*))))
        (format t "~8a ~a~%" kind
         (objc:invoke-into 'string string "substringWithRange:" range))))))

(detect
 "Ship it on September 1, 2026, read https://ik.am, or call 090-1234-5678.")

;;; 5. A calendar nobody implemented here
;;;
;;; The date is a Lisp number -- seconds since the epoch. Foundation carries the era
;;; names, so the Japanese calendar costs one locale identifier.

(format t "~%== 5. one number, two calendars ==~%")

(defun formatted (seconds locale style)
  (let ((formatter
         (objc:invoke (objc:invoke "NSDateFormatter" "alloc") "init")))
    (objc:invoke formatter "setLocale:"
                 (objc:invoke (objc:invoke "NSLocale" "alloc")
                              "initWithLocaleIdentifier:" locale))
    (objc:invoke formatter "setTimeZone:"
                 (objc:invoke "NSTimeZone" "timeZoneWithName:" "Asia/Tokyo"))
    (objc:invoke formatter "setDateStyle:" style)
    (objc:invoke-into 'string formatter "stringFromDate:"
     (objc:invoke "NSDate" "dateWithTimeIntervalSince1970:" seconds))))

(defvar *new-year* 1767225600.0) ; seconds since the epoch, and nothing more

(format t "en_US   ~a~%" (formatted *new-year* "en_US" 3))
(format t "ja_JP   ~a~%" (formatted *new-year* "ja_JP@calendar=japanese" 3))

;;; 6. A string out through Core Image and back in through Vision
;;;
;;; Core Image draws the attributed string into an image, and Vision reads the image.
;;; The composite over white is not decoration: text recognition wants dark on light,
;;; and the generator's output is dark on TRANSPARENT, which Vision reads as nothing --
;;; the filter chain below is the fix, and it is built the way a Lisp builds anything,
;;; by naming each stage and passing values along.
;;;
;;; Neither half is a library this project wrote. The Lisp string goes out through one
;;; framework and comes back through another, and `equal` decides whether the machine
;;; read what it was given.

(format t "~%== 6. text -> image -> text ==~%")

(defun filter (name) (objc:invoke "CIFilter" "filterWithName:" name))

(defun rendered-text (text)
  (let ((generator (filter "CIAttributedTextImageGenerator"))
        (attributed
         (objc:invoke (objc:invoke "NSAttributedString" "alloc")
                      "initWithString:" text)))
    (objc:invoke generator "setValue:forKey:" attributed "inputText")
    (objc:invoke generator "setValue:forKey:"
                 (objc:invoke "NSNumber" "numberWithDouble:" 6.0)
                 "inputScaleFactor")
    (objc:invoke generator "outputImage")))

;; A CGRect is a vector #(x y width height), both as the answer of extent and as the
;; argument of imageByCroppingToRect:.
(defun over-white (image)
  (let ((white (filter "CIConstantColorGenerator"))
        (composite (filter "CISourceOverCompositing")))
    (objc:invoke white "setValue:forKey:"
                 (objc:invoke "CIColor" "colorWithRed:green:blue:" 1.0 1.0 1.0)
                 "inputColor")
    (objc:invoke composite "setValue:forKey:" image "inputImage")
    (objc:invoke composite "setValue:forKey:"
                 (objc:invoke (objc:invoke white "outputImage")
                              "imageByCroppingToRect:"
                              (objc:invoke image "extent"))
                 "inputBackgroundImage")
    (objc:invoke composite "outputImage")))

;; A Lisp vector passed where an object goes becomes an NSArray, which is what
;; performRequests:error: takes.
(defun recognized-text (image)
  (let ((handler
         (objc:invoke (objc:invoke "VNImageRequestHandler" "alloc")
                      "initWithCIImage:options:" image
                      (objc:invoke "NSDictionary" "dictionary")))
        (request
         (objc:invoke (objc:invoke "VNRecognizeTextRequest" "alloc") "init")))
    (objc:invoke-with-error handler "performRequests:error:" (vector request))
    (let ((results (objc:invoke request "results")) (lines nil))
      (dotimes (i (objc:invoke results "count"))
        (let ((candidate
               (objc:invoke (objc:invoke
                             (objc:invoke results "objectAtIndex:" i)
                             "topCandidates:" 1) "objectAtIndex:" 0)))
          (setq lines
                (cons (objc:invoke-into 'string candidate "string") lines))))
      (reverse lines))))

(defvar *written* "rontolisp")
(defvar *image* (over-white (rendered-text *written*)))
(defvar *read-back* (recognized-text *image*))

(format t "wrote     ~s~%" *written*)
(format t "image     ~a points~%" (objc:invoke *image* "extent"))
(format t "read back ~s~%" (car *read-back*))
(format t "round trip ~a~%" (equal *written* (car *read-back*)))

;;; 7. The machine reads it aloud, into a file
;;;
;;; `startSpeakingString:toURL:` synthesizes to an AIFF instead of the speakers, which
;;; is why this example is silent and can be checked. Speech is asynchronous, so the
;;; loop below is the whole of the synchronization: ask, then wait until it stops.

(format t "~%== 7. speech, written to a file ==~%")

(defun speak-to-file (text path)
  (let ((synthesizer
         (objc:invoke (objc:invoke "NSSpeechSynthesizer" "alloc") "init")))
    (objc:invoke synthesizer "startSpeakingString:toURL:" text
                 (objc:invoke "NSURL" "fileURLWithPath:" path))
    (do ()
        ((not (objc:invoke-bool synthesizer "isSpeaking")) path)
      (sleep 0.05))))

(defun file-size (path)
  (let ((attributes
         (objc:invoke-with-error (objc:invoke "NSFileManager" "defaultManager")
                                 "attributesOfItemAtPath:error:" path)))
    (objc:invoke (objc:invoke attributes "objectForKey:" "NSFileSize")
                 "doubleValue")))

(defun temporary-file (name)
  (objc:invoke-into 'string
                    (objc:invoke (objc:invoke
                                  (objc:invoke "NSFileManager" "defaultManager")
                                  "temporaryDirectory")
                                 "URLByAppendingPathComponent:" name) "path"))

(defvar *aiff*
  (speak-to-file (car *read-back*) (temporary-file "rontolisp-speech.aiff")))

(format t "spoke ~s into ~a~%" (car *read-back*)
        (objc:invoke-into 'string (objc:string-to-ns-string *aiff*)
                          "lastPathComponent"))
(format t "the file has audio in it: ~a~%" (> (file-size *aiff*) 0))

(format t "~%nothing was installed, and no window was opened~%")
