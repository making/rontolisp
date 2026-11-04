package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * An ASDF subset integration target ({@code .kb/asdf.md}): Ryan Pavlik's REAL fast-io
 * (MIT) and Eitaro Fukamachi's REAL circular-streams (LLGPL), vendored unmodified under
 * {@code src/test/resources/{fast-io,circular-streams}}, load via
 * {@code asdf:load-system} over the vendored alexandria and the built-in
 * trivial-gray-streams shim, and run the buffered-output / circular-input API on all four
 * backends via {@link AsdfLibraryE2eSupport}.
 *
 * <p>
 * Two general mechanisms had to land before {@code fast-io.asd} could even be read. (1)
 * The {@code .asd} parser accepts the feature-announcement idiom -- a top-level
 * {@code eval-when} pushing onto {@code *features*} -- and records the push as a declared
 * feature of the systems defined after it; fast-io's second, {@code #+}-gated push is off
 * here, so its {@code #+fast-io-sv :static-vectors} dependency correctly drops. (2)
 * {@code with-slots} binds a slot variable through a boundness-guarded read, so a body
 * that only WRITES an {@code :initform}-less slot no longer signals {@code unbound-slot}
 * on entry -- fast-io's {@code initialize-instance} fills its {@code buffer} slot exactly
 * that way, and {@code (make-instance 'fast-io:fast-input-stream ...)} died on it.
 *
 * <p>
 * A third one is exercised here only because fast-io carries a typo: its
 * {@code open-stream-p} method reads a {@code 'openep} slot no class declares. The
 * eagerly expanding compile paths used to fail the whole BUILD on that read; it lowers to
 * a run-time error now, like the interpreter (which expands a method body only when it is
 * called) and like CL's {@code slot-missing}.
 *
 * <p>
 * The circular-input exercise reads past EOF through a Gray source of its own rather than
 * through the fast-io input stream: fast-io's {@code stream-read-byte} passes no
 * {@code eof-error-p} to {@code fast-read-byte}, so it SIGNALS instead of answering
 * {@code :eof} and circular-streams can never see the end of such a source. Verified
 * against SBCL 2.2.9, which fails identically -- an upstream incompatibility between the
 * two libraries, not a rontolisp gap.
 */
class FastIoCircularStreamsE2eTest extends AsdfLibraryE2eSupport {

	private static final String SYSTEM_DIR = Path.of("src", "test", "resources", "circular-streams")
		.toAbsolutePath()
		.toString();

	private static final List<String> DEPENDENCY_DIRS = List.of(
			Path.of("src", "test", "resources", "fast-io").toAbsolutePath().toString(),
			Path.of("src", "test", "resources", "alexandria").toAbsolutePath().toString());

	private static final String EXERCISE = """
			(asdf:load-system :circular-streams)

			;; A binary Gray source answering :eof at the end -- the read-side convention
			;; circular-streams needs to see the end and wrap its position back to 0.
			(defclass byte-source (trivial-gray-streams:trivial-gray-stream-mixin
			                       trivial-gray-streams:fundamental-binary-input-stream)
			  ((bytes :initarg :bytes)
			   (pos :initform 0)))

			(defmethod trivial-gray-streams:stream-read-byte ((s byte-source))
			  (with-slots (bytes pos) s
			    (if (< pos (length bytes))
			        (prog1 (aref bytes pos) (incf pos))
			        :eof)))

			;; fast-io's output stream: initialize-instance fills the :initform-less buffer
			;; slot through (with-slots (buffer) self (setf buffer ...)).
			(let ((out (make-instance 'fast-io:fast-output-stream)))
			  (write-byte 1 out)
			  (write-byte 2 out)
			  (format t "~a~%" (coerce (fast-io:finish-output-stream out) 'list)))

			;; circular-streams over a fast-io:fast-input-stream.
			(let* ((fin (make-instance 'fast-io:fast-input-stream
			                           :vector (make-array 3 :element-type '(unsigned-byte 8)
			                                                 :initial-contents '(10 20 30))))
			       (cs (circular-streams:make-circular-input-stream fin)))
			  (format t "~a~%" (loop repeat 3 collect (read-byte cs)))
			  (format t "~a~%" (file-position cs)))

			;; read-byte past EOF wraps the position back to 0, and (setf
			;; stream-file-position) moves it through file-position.
			(let ((cs (circular-streams:make-circular-input-stream
			           (make-instance 'byte-source :bytes #(10 20 30)))))
			  (format t "~a~%" (loop repeat 7 collect (read-byte cs nil :eof)))
			  (format t "~a~%" (file-position cs))
			  (file-position cs 1)
			  (format t "~a~%" (file-position cs))
			  (format t "~a~%" (list (read-byte cs) (read-byte cs))))

			;; fast-io's gray.lisp defines close/open-stream-p/... methods, which
			;; used to poison the built-ins for real stream handles -- any later
			;; with-open-file died with "No applicable method: CLOSE on INTEGER" (and the
			;; compile paths silently ignored the methods instead). The built-in must stay
			;; each generic's default method, so a real file round-trip after the load works.
			(with-open-file (fio-out "target/fio-roundtrip-%%WORK%%.tmp" :direction :output)
			  (write-line "roundtrip" fio-out))
			(with-open-file (fio-in "target/fio-roundtrip-%%WORK%%.tmp")
			  (format t "~a~%" (open-stream-p fio-in))
			  (format t "~a~%" (read-line fio-in)))

			;; The three input generics the portable package names beside the rest: the
			;; defaults over the element methods, then a class's own methods.
			(defclass char-source (trivial-gray-streams:fundamental-character-input-stream)
			  ((chars :initarg :chars)))

			(defmethod trivial-gray-streams:stream-read-char ((s char-source))
			  (with-slots (chars) s
			    (if chars (pop chars) :eof)))

			(defmethod trivial-gray-streams:stream-unread-char ((s char-source) c)
			  (with-slots (chars) s (push c chars))
			  nil)

			(defclass own-source (char-source) ((calls :initform nil)))

			(defmethod trivial-gray-streams:stream-read-char-no-hang ((s own-source))
			  (push :no-hang (slot-value s 'calls))
			  nil)

			(defmethod trivial-gray-streams:stream-peek-char ((s own-source))
			  (push :peek (slot-value s 'calls))
			  #\\p)

			(defmethod trivial-gray-streams:stream-clear-input ((s own-source))
			  (push :clear (slot-value s 'calls))
			  :ignored)

			(let ((s (make-instance 'char-source :chars (list #\\a #\\b))))
			  (format t "~s~%" (list (peek-char nil s) (read-char-no-hang s) (read-char s)
			                         (read-char-no-hang s nil :end) (peek-char nil s nil :end)
			                         (clear-input s))))
			(let ((s (make-instance 'own-source :chars (list #\\a))))
			  (format t "~s~%" (list (read-char-no-hang s) (peek-char nil s) (clear-input s)
			                         (reverse (slot-value s 'calls)))))
			""";

	private static final List<String> EXPECTED = List.of("(1 2)", "(10 20 30)", "3", "(10 20 30 EOF 10 20 30)", "3",
			"1", "(20 30)", "T", "roundtrip", "(#\\a #\\a #\\b :END :END NIL)",
			"(NIL #\\p NIL (:NO-HANG :PEEK :CLEAR))");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
	}

	@Override
	protected List<String> extraSystemPath() {
		return DEPENDENCY_DIRS;
	}

	@Override
	protected String exercise() {
		return EXERCISE;
	}

	@Override
	protected List<String> expected() {
		return EXPECTED;
	}

	@Override
	protected String artifactName() {
		return "TestFastIoCircularStreams";
	}

}
