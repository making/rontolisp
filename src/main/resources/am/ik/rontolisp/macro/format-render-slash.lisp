;;; The ~/name/ arm of the runtime `format` renderer (format-render.lisp), split
;;; out because it is the ONE part of the renderer that turns runtime data into a
;;; function.
;;;
;;; The renderer is spliced into every program that formats a computed control
;;; string, so whatever sits here is carried by all of them. The find-symbol /
;;; intern / fboundp below can name ANY function, which is exactly the condition
;;; under which --optimize's funcall-dispatch gate has to keep every function
;;; dispatchable (.kb/optimize-dead-code-elimination.md) -- one directive's arm
;;; was holding the gate open for every library program. So the compile path
;;; injects this file only when it can SEE a ~/name/ directive in some control
;;; string, or under --dynamic; otherwise format-render-slash-stub.lisp defines
;;; %fmt-user-function instead. The interpreter always loads this one.

;;; ~/name/: call the named function as (name stream object colon-p at-p) and
;;; splice what it writes. The name is looked up as if by find-symbol, where a
;;; single and a double colon are equivalent (CLHS 22.3.5.4) -- so the INTERNAL
;;; spelling is tried first (that is the canonical one for a symbol the package
;;; does not export) and the external one second.
;;; The string stream is opened and closed by hand rather than with
;;; with-output-to-string on purpose: the WASM exception-handling gate scans the
;;; program for a with-* form, and the renderer is spliced into EVERY program
;;; that formats a computed control string -- one with-output-to-string here
;;; would put a tag section into modules that catch nothing
;;; (.kb/wasi-component.md). This IS the shape with-output-to-string lowers to
;;; outside EH mode.
(defun %fmt-user-function (ctrl end all out pos i colon at)
  (let* ((stop (%fmt-slash-end ctrl pos end))
         (fn (%fmt-function-designator (string-upcase (subseq ctrl pos stop))))
         (stream (%make-string-output-stream)))
    (funcall fn stream (nth i all) colon at)
    (let ((text (%string-stream-contents stream)))
      (close stream)
      (list (%fmt-cat out text) (+ stop 1) (+ i 1) nil))))

(defun %fmt-function-designator (name)
  (let ((k (%fmt-colon-index name)))
    (if (< k 0)
        (intern name)
        (let* ((pkg (subseq name 0 k))
               (member (if (and (< (+ k 1) (length name)) (char= (char name (+ k 1)) #\:))
                           (subseq name (+ k 2))
                           (subseq name (+ k 1))))
               (found (find-symbol member pkg))
               (internal (intern (concatenate 'string pkg "::" member)))
               (external (intern (concatenate 'string pkg ":" member))))
          ;; find-symbol answers the canonical spelling wherever a live symbol
          ;; table exists; the two built spellings cover the compiled backends,
          ;; where a symbol IS its spelling and the internal one is the common
          ;; case (a library rarely exports the function it names in a ~/.../).
          (cond ((and found (fboundp found)) found)
                ((fboundp internal) internal)
                ((fboundp external) external)
                ((null found) internal)
                (t found))))))

(defun %fmt-colon-index (name)
  (let ((p 0) (n (length name)) (res -1))
    (while (and (< p n) (< res 0))
      (if (char= (char name p) #\:) (setq res p) (setq p (+ p 1))))
    res))
