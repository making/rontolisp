;;;; audio.lisp -- a Lisp closure as an instrument: AVAudioEngine asks a block for
;;;; samples, and the block asks a Lisp function of time for each one.
;;;;
;;;; An AVAudioSourceNode is a node of the audio graph whose output is whatever its
;;;; render block writes. The block below is made from a Lisp function, so every
;;;; sample the engine mixes -- into a file or into the speakers -- is a number a Lisp
;;;; closure answered for one instant.
;;;;
;;;; What decides where it can run is WHICH THREAD CALLS THE BLOCK, and the program
;;;; measures it with pthread_main_np:
;;;;
;;;;   - Offline (manual rendering, sections 2-4): `renderOffline:toBuffer:error:` runs
;;;;     the graph inside the send, so the block is called on the thread that sent it --
;;;;     the main thread on every target, since the interpreter and the JVM send there
;;;;     and a --native program runs there. No audio device is involved: the output is
;;;;     silent, needs no sound hardware and is the same on every target.
;;;;   - Live (section 5, only when given the argument `play`): the engine calls the
;;;;     block on its real-time I/O thread, never the main thread. The interpreter and
;;;;     the JVM run it there, concurrently with the program. A --native executable
;;;;     cannot: its program lives on the main thread, and a block that answers a value
;;;;     (this one answers an OSStatus) is refused on any other thread -- printed, and
;;;;     answered with zero, about ninety times a second. So a --native executable
;;;;     skips section 5.
;;;;
;;;; A live render block has two further rules. It must send no message: a send waits
;;;; for the main thread, and `-[AVAudioEngine stop]`, sent on the main thread, waits for
;;;; the render thread, so the two would wait for each other. That is why the samples
;;;; are written with `fli:`, which touches memory on the calling thread. And it has a
;;;; deadline, about 23 us a sample at 44.1 kHz. Measured (M4 Max, two seconds): a
;;;; compiled class or jar renders every frame; the interpreter, whose `fli:` write
;;;; costs about 35 us, renders a third to a half of them under `java -jar` and a
;;;; quarter to a third in the rontolisp binary, which is heard as a stutter. Section 5
;;;; prints the count.
;;;;
;;;; macOS only: on the interpreter, compiled to a JVM class or jar, and as a --native
;;;; executable on Apple silicon (offline only); never as WASM.
;;;;
;;;;   java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar examples/macos/audio.lisp [-- play]
;;;;   ./target/rontolisp examples/macos/audio.lisp [-- play]
;;;;   ./target/rontolisp examples/macos/audio.lisp -o Audio.class --class-name Audio && java Audio [play]
;;;;   ./target/rontolisp examples/macos/audio.lisp -o audio.jar && java -jar audio.jar [play]
;;;;   ./target/rontolisp examples/macos/audio.lisp --native -o audio && ./audio

;;; 1. The framework, and the two C structures a render block is handed
;;;
;;; AVAudioEngine lives in AVFAudio, which nothing links into this process: loading it
;;; is the `:modules` argument. The block receives an AudioBufferList -- a count, then
;;; one AudioBuffer per channel -- by pointer. Declaring both with
;;; `objc:define-objc-struct` lets `fli:foreign-slot-value` find the sample memory by
;;; slot name, the offsets worked out by the C layout rule. The format below is mono, so
;;; the list holds one buffer and one slot of the array is all that is declared.

(format t "== 1. AVFAudio ==~%")

(objc:ensure-objc-initialized
 :modules '("/System/Library/Frameworks/AVFAudio.framework/AVFAudio"))

(format t "a class now: ~a~%"
        (objc:objc-class-name (objc:coerce-to-objc-class "AVAudioEngine")))

(objc:define-objc-struct (audio-buffer (:foreign-name "AudioBuffer"))
  (:channels (:unsigned :int))
  (:byte-size (:unsigned :int))
  (:data (:pointer :float)))

(objc:define-objc-struct (audio-buffer-list (:foreign-name "AudioBufferList"))
  (:count (:unsigned :int))
  (:buffers audio-buffer))

(format t "sizeof(AudioBufferList) with one buffer: ~a bytes~%"
        (fli:size-of 'audio-buffer-list))

;;; The render block
;;;
;;; OSStatus (^)(BOOL *isSilence, const AudioTimeStamp *timestamp,
;;;              AVAudioFrameCount frameCount, AudioBufferList *outputData)
;;;
;;; A method's encoding says only that it takes a block, never what the block takes, so
;;; the signature is ours to state -- once, as a named block type.

(objc:define-objc-block-type render-block
  :int ((:pointer objc:objc-bool) (:pointer :void) (:unsigned :int)
        (:pointer audio-buffer-list)))

(fli:define-foreign-function (pthread-main-np "pthread_main_np")
  ()
  :result-type :int)

(defconstant +rate+ 44100)

;; The threads the render block was called on: :main or :other, newest first.
(defvar *block-threads* nil)

;; The function a render block is made from: it fills each buffer by asking INSTRUMENT
;; for the amplitude at each frame's time in seconds. The frame counter lives in the
;; closure, so time runs on across calls however the engine slices the buffers.
(defun render-function (instrument)
  (let ((frame 0))
    (lambda (silence timestamp count buffers)
      (declare (ignore silence timestamp))
      (let ((thread (if (= (pthread-main-np) 1) :main :other))
            (samples (fli:foreign-slot-value buffers '(:buffers :data))))
        (unless (eq thread (car *block-threads*)) (push thread *block-threads*))
        (dotimes (i count)
          (setf (fli:dereference samples :index i)
                (funcall instrument (/ (float (+ frame i) 1d0) +rate+))))
        (setq frame (+ frame count))
        0)))) ; noErr

;; An instrument is a function from a time in seconds to an amplitude in -1..1.
(defun sine (frequency &optional (amplitude 0.25))
  (let ((w (* 2 pi frequency))) (lambda (time) (* amplitude (sin (* w time))))))

(defun fm (carrier ratio depth &optional (amplitude 0.25))
  (let ((wc (* 2 pi carrier)) (wm (* 2 pi carrier ratio)))
    (lambda (time)
      (* amplitude (sin (+ (* wc time) (* depth (sin (* wm time)))))))))

(defun chord (frequencies &optional (amplitude 0.25))
  (let ((voices (mapcar (lambda (f) (sine f 1.0)) frequencies))
        (scale (/ amplitude (length frequencies))))
    (lambda (time)
      (let ((sum 0.0))
        (dolist (voice voices) (setq sum (+ sum (funcall voice time))))
        (* scale sum)))))

(defun mono-format ()
  (objc:invoke (objc:invoke "AVAudioFormat" "alloc")
               "initStandardFormatWithSampleRate:channels:" (float +rate+ 1d0)
               1))

;; A source node playing BLOCK in FORMAT, wired into ENGINE's main mixer.
(defun attach-source (engine format block)
  (let ((node
         (objc:invoke (objc:invoke "AVAudioSourceNode" "alloc")
                      "initWithFormat:renderBlock:" format block)))
    (objc:invoke engine "attachNode:" node)
    (objc:invoke engine "connect:to:format:" node
                 (objc:invoke engine "mainMixerNode") format)
    node))

;;; Offline: the graph run as fast as it goes, inside one send
;;;
;;; Manual rendering detaches the engine from the audio device. Each
;;; `renderOffline:toBuffer:error:` pulls up to +CHUNK+ frames through the graph and
;;; returns when they are in the buffer -- so the block has run, on this send's thread,
;;; before the call answers. The status is an enum whose zero means success, so the
;;; check is ours: `objc:invoke-with-error` only recognises nil, NO and zero as failure.
;;; The block lives for the extent of `objc:with-objc-block`; the node keeps a copy of
;;; its own for as long as it needs one.

(defconstant +chunk+ 4096)

;; Renders SECONDS of INSTRUMENT offline and calls EACH with the output buffer and the
;; number of frames in it, once per chunk.
(defun render-offline (instrument seconds each)
  (let ((frames (round (* seconds +rate+)))
        (engine (objc:alloc-init-object "AVAudioEngine"))
        (format (mono-format)))
    (objc:with-objc-block (block 'render-block (render-function instrument))
      (attach-source engine format block)
      (objc:invoke-with-error engine
       "enableManualRenderingMode:format:maximumFrameCount:error:" 0 format
       +chunk+)
      (objc:invoke-with-error engine "startAndReturnError:")
      (unwind-protect (let ((buffer
                             (objc:invoke
                              (objc:invoke "AVAudioPCMBuffer" "alloc")
                              "initWithPCMFormat:frameCapacity:"
                              (objc:invoke engine "manualRenderingFormat")
                              +chunk+))
                            (done 0))
                        (loop while (< done frames)
                              do
                                (let ((status
                                       (objc:invoke-with-error engine
                                        "renderOffline:toBuffer:error:"
                                        (min +chunk+ (- frames done)) buffer))
                                      (got (objc:invoke buffer "frameLength")))
                                  (unless (and (= status 0) (> got 0))
                                    (error "offline rendering stopped: status ~a, ~a frame(s)"
                                           status got))
                                  (funcall each buffer got)
                                  (setq done (+ done got)))))
        (objc:invoke engine "stop")))))

;; The samples that came OUT of the engine -- after the mixer -- as a vector.
(defun synthesize (instrument seconds)
  (let ((out
         (make-array (round (* seconds +rate+)) :element-type 'single-float))
        (at 0))
    (render-offline instrument seconds
                    (lambda (buffer got)
                      (let ((channel
                             (fli:dereference
                              (objc:invoke buffer "floatChannelData"))))
                        (dotimes (i got)
                          (setf (aref out (+ at i))
                                (fli:dereference channel :index i)))
                        (setq at (+ at got)))))
    out))

(defun upward-crossings (samples)
  (let ((n 0))
    (dotimes (i (- (length samples) 1) n)
      (when (and (< (aref samples i) 0) (>= (aref samples (+ i 1)) 0))
        (setq n (+ n 1))))))

(defun peak (samples)
  (let ((m 0.0))
    (dotimes (i (length samples) m) (setq m (max m (abs (aref samples i)))))))

;;; 2. A tone, and what can be checked about it

(format t "~%== 2. offline: a 440 Hz sine, a tenth of a second ==~%")

(setq *block-threads* nil)
(defvar *a440* (synthesize (sine 440 0.5) 1/10))

;; 4410 frames are two sends (4096, then 314), and the frame counter carries across.
;; The upward zero crossings measure the frequency of what came back: 4410 frames of
;; 440 Hz are 44 cycles, and a crossing starts each one -- but the first sits on frame
;; 0, with nothing before it, and the one after the last on frame 4410, past the end.
(format t "frames              ~a~%" (length *a440*))
(format t "block ran on        ~a~%" *block-threads*)
(format t "first sample        ~,4f~%" (aref *a440* 0))
(format t "upward crossings    ~a~%" (upward-crossings *a440*))

;; The instrument's amplitude is 0.5, and what comes out of the main mixer peaks at
;; 0.5/sqrt(2) -- what an equal-power pan law gives a centred mono source. The pan law
;; is the likely reason; the number is the measurement, and the line pins it.
(format t "peak                ~,4f (0.5/sqrt 2 = ~,4f)~%" (peak *a440*)
        (/ 0.5 (sqrt 2)))

;;; 3. Other instruments

(format t "~%== 3. offline: other instruments ==~%")

(defvar *a100* (synthesize (sine 100 0.5) 1/2))
(format t "100 Hz for half a second crosses upward ~a times~%"
        (upward-crossings *a100*))

(defvar *fm* (synthesize (fm 220 2.5 3) 1/10))
(format t "an FM tone differs from the sine: ~a~%" (not (equalp *fm* *a440*)))
(format t "and stays in range: ~a~%" (<= (peak *fm*) 1.0))

;;; 4. A file nobody wrote a header for
;;;
;;; AVAudioFile writes a PCM buffer as it comes out of the engine; the container is
;;; chosen by the extension. The buffers go straight from `renderOffline:` to the file
;;; with no Lisp copy in between, and the file is read back for its length and rate.

(format t "~%== 4. a chord, rendered into a .wav ==~%")

(defun temporary-url (name)
  (objc:invoke (objc:invoke (objc:invoke "NSFileManager" "defaultManager")
                            "temporaryDirectory") "URLByAppendingPathComponent:"
               name))

(defun render-to-file (instrument seconds url)
  (let ((file nil))
    (render-offline instrument seconds
                    (lambda (buffer got)
                      (declare (ignore got))
                      (unless file
                        (setq file
                              (objc:invoke-with-error
                               (objc:invoke "AVAudioFile" "alloc")
                               "initForWriting:settings:error:" url
                               (objc:invoke (objc:invoke buffer "format")
                                            "settings"))))
                      (objc:invoke-with-error file "writeFromBuffer:error:"
                                              buffer)))
    ;; The file is finished when the object goes away.
    (objc:release file)
    url))

(defvar *wav*
  (render-to-file (chord '(261.63 329.63 392.0)) 1/4
                  (temporary-url "rontolisp-chord.wav")))

(let ((file
       (objc:invoke-with-error (objc:invoke "AVAudioFile" "alloc")
                               "initForReading:error:" *wav*)))
  (format t "~a: ~a frames at ~a Hz~%"
          (objc:invoke-into 'string *wav* "lastPathComponent")
          (objc:invoke file "length")
          (round (objc:invoke (objc:invoke file "fileFormat") "sampleRate"))))

;;; 5. Live, when asked
;;;
;;; The same instrument through the speakers. The engine is left in its ordinary mode,
;;; so it pulls on the I/O thread at the device's pace, and the program sleeps while it
;;; plays. The frames the block rendered, against the frames that much time holds, say
;;; whether it kept up.

(defun play (instrument seconds)
  (let ((engine (objc:alloc-init-object "AVAudioEngine")) (rendered 0))
    (objc:with-objc-block (block 'render-block
                            (let ((render (render-function instrument)))
                              (lambda (silence timestamp count buffers)
                                (setq rendered (+ rendered count))
                                (funcall render silence timestamp count
                                         buffers))))
      (attach-source engine (mono-format) block)
      (objc:invoke-with-error engine "startAndReturnError:")
      (unwind-protect (sleep seconds)
        ;; Stopped before the block is freed, so no callback is still in flight.
        (objc:invoke engine "stop")))
    (format t "block ran on ~a; rendered ~a frames in ~a s (real time: ~a)~%"
            *block-threads* rendered seconds (* seconds +rate+))))

(format t "~%== 5. live ==~%")

(cond
 ((not (member "play" (uiop:command-line-arguments) :test #'equal))
  (format t "not asked to play (pass the argument play)~%"))
 ((member :rontolisp-native *features*)
  (format t
   "not in a --native executable: the I/O thread could not run the block~%"))
 (t
  (setq *block-threads* nil)
  (play (chord '(261.63 329.63 392.0) 0.1) 2)))

(format t "~%every sample above came from a Lisp closure~%")
