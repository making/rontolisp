;;;; Contact book using defstruct in rontolisp
;;;; Demonstrates defstruct (tagged-list representation), hash tables,
;;;; setf accessors, and format directives. Runs on all three backends.
;;;;
;;;; Run:
;;;;   rontolisp examples/console/contact-book.lisp
;;;;   rontolisp examples/console/contact-book.lisp -o ContactBook.class && java ContactBook
;;;;   rontolisp examples/console/contact-book.lisp -o contact-book.wasm && wasmtime run -W gc contact-book.wasm

(defstruct contact
  name
  email
  phone
  notes)

(defun make-book ()
  "Create an empty contact book (hash table keyed by name)."
  (make-hash-table))

(defun add-contact (book &key name email phone notes)
  "Add a contact to the book. Returns the contact."
  (let ((c (make-contact :name name :email email :phone phone :notes notes)))
    (setf (gethash name book) c)
    c))

(defun find-contact (book name)
  "Look up a contact by name."
  (gethash name book))

(defun update-email (book name new-email)
  "Update a contact's email using (setf contact-email)."
  (let ((c (find-contact book name)))
    (if c
        (progn
          (setf (contact-email c) new-email)
          t)
        nil)))

(defun list-contacts (book)
  "Return all contacts in the book as a list."
  (let ((result nil))
    (maphash (lambda (name contact)
               (push contact result))
             book)
    result))

;;; Build a sample contact book
(let ((book (make-book)))
  (add-contact book :name "Alice" :email "alice@example.com" :phone "555-0101" :notes "Developer")
  (add-contact book :name "Bob" :email "bob@example.com" :phone "555-0102" :notes "Designer")
  (add-contact book :name "Carol" :email "carol@example.com" :phone "555-0103" :notes "Manager")

  (format t "Contact Book (~d entries):~%" (hash-table-count book))
  (format t "~%All contacts:~%")
  (dolist (c (list-contacts book))
    (format t "  ~a | ~a | ~a | ~a~%"
            (contact-name c)
            (contact-email c)
            (contact-phone c)
            (contact-notes c)))

  ;; Update an email
  (update-email book "Alice" "alice@newdomain.com")
  (format t "~%After updating Alice's email:~%")
  (let ((alice (find-contact book "Alice")))
    (format t "  ~a -> ~a~%" (contact-name alice) (contact-email alice)))

  ;; Search by notes
  (format t "~%Contacts with 'Developer' in notes:~%")
  (dolist (c (list-contacts book))
    (when (string= (contact-notes c) "Developer")
      (format t "  ~a (~a)~%" (contact-name c) (contact-email c)))))
