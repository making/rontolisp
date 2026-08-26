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
;;;; It prints to the terminal and ends by itself. macOS only, on the interpreter and
;;;; compiled to a JVM class or jar; never as WASM.
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/system-frameworks.lisp
;;;;   ./target/rontolisp examples/macos/system-frameworks.lisp
;;;;   ./target/rontolisp examples/macos/system-frameworks.lisp -o SystemFrameworks.class \
;;;;     --class-name SystemFrameworks && java SystemFrameworks
;;;;   ./target/rontolisp examples/macos/system-frameworks.lisp -o system-frameworks.jar && \
;;;;     java -jar system-frameworks.jar

(defun utf8 (nsstring) (objc:send nsstring "UTF8String"))

(defun nsstr (text) (objc:string text))

;;; 1. A dependency is a message
;;;
;;; Vision, NaturalLanguage and Core Image are not linked into this process: no Lisp
;;; program links anything. `load` maps the framework and registers its classes with the
;;; runtime, and from the next line on `(objc:send "VNRecognizeTextRequest" ...)` names a
;;; class that did not exist a moment ago. This is the whole of dependency management
;;; here -- there is no manifest, no classpath and no download.

(format t "== 1. a dependency is a message ==~%")

(defun load-framework (name)
  (let ((bundle
         (objc:send "NSBundle" "bundleWithPath:"
          (nsstr (format nil "/System/Library/Frameworks/~a.framework" name)))))
    (if (and bundle (objc:send bundle "load")) t nil)))

(dolist (name '("Vision" "NaturalLanguage" "CoreImage"))
  (format t "~16a loaded=~a~%" name (load-framework name)))

;;; 2. Which language is this?
;;;
;;; NLLanguageRecognizer is a model, shipped with the system and already on disk. The
;;; call is one message and the answer is a BCP-47 tag.

(format t "~%== 2. the language of a string ==~%")

(defun dominant-language (text)
  (utf8
   (objc:send "NLLanguageRecognizer" "dominantLanguageForString:"
              (nsstr text))))

(dolist (text
         '("これは日本語の文章です" "this sentence is in english"
           "cette phrase est en français"))
  (format t "~a -> ~a~%" (dominant-language text) text))

;;; 3. Spelling, from the same checker the text fields use
;;;
;;; `checkSpellingOfString:startingAt:` answers an NSRange -- a C struct, which the
;;; binding flattens to a Lisp list, because the selector's own type encoding says so
;;; and nothing here declared it.

(format t "~%== 3. spelling ==~%")

(defvar *checker* (objc:send "NSSpellChecker" "sharedSpellChecker"))

(defun misspelling (text)
  (objc:send *checker* "checkSpellingOfString:startingAt:" (nsstr text) 0))

(defun guesses (text range)
  (let ((array
         (objc:send *checker*
          "guessesForWordRange:inString:language:inSpellDocumentWithTag:" range
          (nsstr text) (nsstr "en") 0)))
    (utf8 (objc:send array "componentsJoinedByString:" (nsstr ", ")))))

(defvar *typo* "i recieve mail")
(defvar *range* (misspelling *typo*))

(format t "~s: first misspelling at ~a~%" *typo* *range*)
(format t "guesses: ~a~%" (guesses *typo* *range*))

;;; 4. Structure pulled out of prose
;;;
;;; NSDataDetector is the machinery behind the blue underlines in Mail. The mask is a
;;; plain integer, the matches are an NSArray, and each match carries the range it
;;; found -- so a Lisp program gets dates, links and phone numbers out of free text
;;; with no regular expression of its own.

(format t "~%== 4. dates, links and numbers in free text ==~%")

(defvar *checking-types*
  (list (cons 8 "date") (cons 16 "address") (cons 32 "link")
        (cons 2048 "phone")))

(defun detector ()
  (objc:send "NSDataDetector" "dataDetectorWithTypes:error:"
             (reduce #'+ (mapcar #'car *checking-types*)) nil))

(defun detect (text)
  (let* ((string (nsstr text))
         (matches
          (objc:send (detector) "matchesInString:options:range:" string 0
                     (list 0 (objc:send string "length")))))
    (dotimes (i (objc:send matches "count"))
      (let* ((match (objc:send matches "objectAtIndex:" i))
             (range (objc:send match "range"))
             (kind
              (cdr (assoc (objc:send match "resultType") *checking-types*))))
        (format t "~8a ~a~%" kind
                (utf8 (objc:send string "substringWithRange:" range)))))))

(detect
 "Ship it on September 1, 2026, read https://ik.am, or call 090-1234-5678.")

;;; 5. A calendar nobody implemented here
;;;
;;; The date is a Lisp number -- seconds since the epoch. Foundation carries the era
;;; names, so the Japanese calendar costs one locale identifier.

(format t "~%== 5. one number, two calendars ==~%")

(defun formatted (seconds locale style)
  (let ((formatter (objc:send (objc:send "NSDateFormatter" "alloc") "init")))
    (objc:send formatter "setLocale:"
               (objc:send (objc:send "NSLocale" "alloc")
                          "initWithLocaleIdentifier:" (nsstr locale)))
    (objc:send formatter "setTimeZone:"
     (objc:send "NSTimeZone" "timeZoneWithName:" (nsstr "Asia/Tokyo")))
    (objc:send formatter "setDateStyle:" style)
    (utf8
     (objc:send formatter "stringFromDate:"
      (objc:send "NSDate" "dateWithTimeIntervalSince1970:" seconds)))))

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

(defun filter (name) (objc:send "CIFilter" "filterWithName:" (nsstr name)))

(defun rendered-text (text)
  (let ((generator (filter "CIAttributedTextImageGenerator"))
        (attributed
         (objc:send (objc:send "NSAttributedString" "alloc") "initWithString:"
                    (nsstr text))))
    (objc:send generator "setValue:forKey:" attributed "inputText")
    (objc:send generator "setValue:forKey:"
               (objc:send "NSNumber" "numberWithDouble:" 6.0)
               "inputScaleFactor")
    (objc:send generator "outputImage")))

(defun over-white (image)
  (let ((white (filter "CIConstantColorGenerator"))
        (composite (filter "CISourceOverCompositing")))
    (objc:send white "setValue:forKey:"
               (objc:send "CIColor" "colorWithRed:green:blue:" 1.0 1.0 1.0)
               "inputColor")
    (objc:send composite "setValue:forKey:" image "inputImage")
    (objc:send composite "setValue:forKey:"
               (objc:send (objc:send white "outputImage")
                          "imageByCroppingToRect:" (objc:send image "extent"))
               "inputBackgroundImage")
    (objc:send composite "outputImage")))

(defun recognized-text (image)
  (let ((handler
         (objc:send (objc:send "VNImageRequestHandler" "alloc")
                    "initWithCIImage:options:" image
                    (objc:send "NSDictionary" "dictionary")))
        (request
         (objc:send (objc:send "VNRecognizeTextRequest" "alloc") "init")))
    (objc:send handler "performRequests:error:"
               (objc:send "NSArray" "arrayWithObject:" request) nil)
    (let ((results (objc:send request "results")) (lines nil))
      (dotimes (i (objc:send results "count"))
        (let ((candidate
               (objc:send (objc:send (objc:send results "objectAtIndex:" i)
                                     "topCandidates:" 1) "objectAtIndex:" 0)))
          (setq lines (cons (utf8 (objc:send candidate "string")) lines))))
      (reverse lines))))

(defvar *written* "rontolisp")
(defvar *image* (over-white (rendered-text *written*)))
(defvar *read-back* (recognized-text *image*))

(format t "wrote     ~s~%" *written*)
(format t "image     ~a points~%" (objc:send *image* "extent"))
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
         (objc:send (objc:send "NSSpeechSynthesizer" "alloc") "init")))
    (objc:send synthesizer "startSpeakingString:toURL:" (nsstr text)
               (objc:send "NSURL" "fileURLWithPath:" (nsstr path)))
    (do ()
        ((not (objc:send synthesizer "isSpeaking")) path)
      (sleep 0.05))))

(defun file-size (path)
  (let ((attributes
         (objc:send (objc:send "NSFileManager" "defaultManager")
                    "attributesOfItemAtPath:error:" (nsstr path) nil)))
    (objc:send (objc:send attributes "objectForKey:" (nsstr "NSFileSize"))
               "doubleValue")))

(defun temporary-file (name)
  (utf8
   (objc:send (objc:send (objc:send (objc:send "NSFileManager" "defaultManager")
                                    "temporaryDirectory")
                         "URLByAppendingPathComponent:" (nsstr name)) "path")))

(defvar *aiff*
  (speak-to-file (car *read-back*) (temporary-file "rontolisp-speech.aiff")))

(format t "spoke ~s into ~a~%" (car *read-back*)
        (utf8 (objc:send (nsstr *aiff*) "lastPathComponent")))
(format t "the file has audio in it: ~a~%" (> (file-size *aiff*) 0))

(format t "~%nothing was installed, and no window was opened~%")
