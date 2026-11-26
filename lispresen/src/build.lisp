;;;; build.lisp -- what lispresen takes from its source tree when it is built: the version,
;;;; the commit and files read as text.
;;;;
;;;; The macros here read files while they expand: on rontolisp's compilers when a jar,
;;;; WASM module or native executable is built, on the interpreter and another Common Lisp
;;;; when the source is loaded. A use must sit in a top-level form, where *LOAD-TRUENAME*
;;;; names the file being loaded or compiled; paths are relative to that file. No backend
;;;; runs a program at that point, so the commit is read from .git as files, not from git.

(in-package :lispresen)

(defun build-directory ()
  "The directory of the file being loaded or compiled, ending in /, or \"\" (the working
directory) when none is."
  (let* ((path (if *load-truename* (namestring *load-truename*) ""))
         (slash (position #\/ path :from-end t)))
    (if slash (subseq path 0 (1+ slash)) "")))

(defun build-file-text (path)
  "The text of the file PATH, each line ending in a newline, or nil when it cannot be read
(a directory included)."
  (handler-case (with-open-file (s path :if-does-not-exist nil)
                  (and s
                       (with-output-to-string (out)
                         (loop for line = (read-line s nil nil)
                               while line
                               do (write-line line out)))))
    (error () nil)))

(defun build-first-line (path)
  "The first line of the file PATH without surrounding blanks, or nil."
  (let ((text (build-file-text path)))
    (and text
         (string-trim '(#\Space #\Tab #\Return)
                      (subseq text 0 (or (position #\Newline text) (length text)))))))

(defun prefixed (prefix line)
  "The rest of LINE after PREFIX, or nil when LINE does not start with it."
  (and line (>= (length line) (length prefix))
       (string= prefix line :end2 (length prefix)) (subseq line (length prefix))))

(defun git-commit (root)
  "The first seven digits of the commit checked out in the work tree at ROOT, or nil. The
git directory is ROOT/.git or, in a linked work tree, the one its .git file names; HEAD
names a commit or a branch, whose ref is a loose file or a line of packed-refs in the
common git directory."
  (let* ((git-dir
          (if (build-first-line (concatenate 'string root ".git/HEAD"))
              (concatenate 'string root ".git/")
              (let ((dir
                     (prefixed "gitdir: "
                               (build-first-line
                                (concatenate 'string root ".git")))))
                (and dir
                     (concatenate 'string
                                  (if (and (> (length dir) 0)
                                           (char= (char dir 0) #\/))
                                      ""
                                      root)
                                  dir "/")))))
         (head (and git-dir (build-first-line (concatenate 'string git-dir "HEAD"))))
         (common
          (let ((dir (and git-dir (build-first-line (concatenate 'string git-dir "commondir")))))
            (if dir (concatenate 'string git-dir dir "/") git-dir)))
         (ref (prefixed "ref: " head))
         (id
          (if ref
              (or (build-first-line (concatenate 'string git-dir ref))
                  (build-first-line (concatenate 'string common ref))
                  (let ((packed (build-file-text (concatenate 'string common "packed-refs"))))
                    (and packed
                         (with-input-from-string (s packed)
                           (loop for line = (read-line s nil nil)
                                 while line
                                 do (let ((space (position #\Space line)))
                                      (when (and space
                                                 (string= ref line :start2 (1+ space)))
                                        (return (subseq line 0 space)))))))))
              head)))
    (and id (>= (length id) 40) (every (lambda (c) (digit-char-p c 16)) id)
         (subseq id 0 7))))

(defun pom-version (root)
  "The version of the project ROOT/pom.xml describes (rontolisp's), or nil."
  (let* ((pom (build-file-text (concatenate 'string root "pom.xml")))
         (artifact (and pom (search "<artifactId>rontolisp</artifactId>" pom)))
         (start (and artifact (search "<version>" pom :start2 artifact)))
         (end (and start (search "</version>" pom :start2 start))))
    (and end (subseq pom (+ start (length "<version>")) end))))

(defmacro build-info ()
  "(VERSION COMMIT) of the repository holding the file this expands in, two levels up:
the version from its pom.xml, the commit checked out. Either is \"unknown\" when it cannot
be read, outside a repository for instance."
  (let ((root (concatenate 'string (build-directory) "../../")))
    `'(,(or (pom-version root) "unknown") ,(or (git-commit root) "unknown"))))

(defmacro build-text (path)
  "The text of the file PATH, relative to the file this expands in."
  (or (build-file-text (concatenate 'string (build-directory) path))
      (error "lispresen: cannot read ~a" path)))

(defparameter *build-info* (build-info))

(defun version-line ()
  "What lispresen --version prints, in the shape rontolisp --version prints."
  (format nil "{\"version\": \"~a\", \"gitCommit\": \"~a\"}"
          (first *build-info*) (second *build-info*)))
