package am.ik.rontolisp;

import java.util.List;

/**
 * A Gray base class named before anything else in the program reached the Gray protocol,
 * shared by the backend suites. Each program's first Gray reference is a different use of
 * a base class NAME -- {@code find-class}, {@code subtypep}, {@code make-instance} with
 * {@code typep}, an unqualified superclass, a method specializer -- so no write, no
 * {@code defclass} over a qualified base class and no protocol definition comes first.
 * sbcl's answers ({@code sb-gray:} for {@code rontolisp:}; cl-user uses sb-gray there,
 * and on rontolisp an unqualified base-class name resolves by its member).
 */
public final class GrayBaseClassNamedFirstFixture {

	private GrayBaseClassNamedFirstFixture() {
	}

	/**
	 * One program and what it prints.
	 *
	 * @param program the whole program
	 * @param expected its printed output
	 */
	public record Case(String program, String expected) {
	}

	/** The programs. */
	public static final List<Case> CASES = List.of(new Case("""
			(print (list (not (null (find-class 'rontolisp:fundamental-stream nil)))
			             (subtypep 'rontolisp:fundamental-stream 'standard-object)))
			""", "(T T)"), new Case("""
			(print (list (subtypep 'rontolisp:fundamental-stream 'standard-object)
			             (subtypep 'rontolisp:fundamental-character-input-stream 'rontolisp:fundamental-stream)
			             (subtypep 'rontolisp:fundamental-binary-output-stream 'stream)
			             (subtypep 'rontolisp:fundamental-output-stream 'rontolisp:fundamental-input-stream)))
			""", "(T T T NIL)"), new Case("""
			(print (typep (make-instance 'rontolisp:fundamental-character-output-stream)
			              'rontolisp:fundamental-output-stream))
			""", "T"), new Case("""
			(defclass gbn-out (fundamental-character-output-stream) ((chars :initform nil :accessor gbn-chars)))
			(defmethod rontolisp:stream-write-char ((s gbn-out) c) (push c (gbn-chars s)) c)
			(let ((s (make-instance 'gbn-out)))
			  (write-string "ok" s)
			  (print (list (coerce (reverse (gbn-chars s)) 'string)
			               (not (null (find-class 'fundamental-stream nil))))))
			""", "(\"ok\" T)"), new Case("""
			(defgeneric gbn-kind (s))
			(defmethod gbn-kind ((s rontolisp:fundamental-stream)) :gray)
			(defmethod gbn-kind ((s t)) :other)
			(print (list (gbn-kind 3) (gbn-kind (make-string-output-stream))))
			""", "(:OTHER :OTHER)"));

}
