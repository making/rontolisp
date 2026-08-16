package am.ik.rontolisp.e2e;

import java.nio.file.Path;
import java.util.List;

/**
 * The babel shim's DECODING-MAPPING protocol ({@code .kb/asdf.md}) on ALL FOUR backends
 * via {@link AsdfLibraryE2eSupport}: {@code lookup-mapping} over
 * {@code babel:*string-vector-mappings*}, the {@code code-point-counter} /
 * {@code octet-counter} / {@code decoder} / {@code encoder} it leads to, the
 * {@code unicode-char} type, {@code enc-max-units-per-char}, the character-coding
 * condition hierarchy and {@code *suppress-character-coding-errors*}.
 *
 * <p>
 * The exercise decodes INCREMENTALLY -- one character at a time out of a shared octet
 * vector, counted with {@code max-chars} 1 to find where that character ends and then
 * decoded into a one-character buffer -- because that is the shape a consumer needs the
 * mapping layer for at all: dexador's {@code src/decoding-stream.lisp} has no whole octet
 * vector to hand to {@code octets-to-string} and drives exactly these five names
 * (todo-398, found by the dexador spike). A driver-only shim answered
 * {@code The symbol UNICODE-CHAR is not external in the BABEL package} at that file's
 * {@code defpackage}, before any of the library's code ran.
 *
 * <p>
 * What the assertions pin beyond "it runs": the counter and the decoder AGREE on where a
 * character ends (every step prints its own {@code new-end}, and the driver
 * {@code octets-to-string} sizes its string from the counter and then fills it with the
 * decoder -- a disagreement is a short or over-long string, not a crash), a truncated
 * multi-byte tail counts as the one substitution character the decoder writes for it, and
 * the condition each malformed shape signals is catchable BOTH by its leaf type and by
 * {@code babel:character-decoding-error}, which is what a consumer's fall-back-to-binary
 * handler names. The suppression special is read by the codec itself rather than by the
 * drivers, so binding it makes the mapping layer answer substitution characters too.
 */
class BabelMappingE2eTest extends AsdfLibraryE2eSupport {

	// babel is a BUILT-IN shim system (eval.ShimLibraries): both loaders resolve it
	// before any --system-path directory is searched, so this only satisfies the base
	// class's contract -- there is no vendored .asd to point at.
	private static final String SYSTEM_DIR = Path.of("src", "test", "resources").toAbsolutePath().toString();

	private static final String EXERCISE = """
			(ql:quickload :babel)
			(defun octs (list)
			  (let ((v (make-array (length list) :element-type '(unsigned-byte 8))) (i 0))
			    (dolist (b list) (setf (aref v i) b) (setq i (+ i 1)))
			    v))
			;; A decoding stream in the shape dexador's src/decoding-stream.lisp has: one
			;; mapping looked up once, then per character a code-point count with
			;; max-chars 1 to find where that character ends and a decode of exactly
			;; those octets into a one-character buffer.
			(defun decode-incrementally (octets encoding)
			  (let* ((enc (babel-encodings:get-character-encoding encoding))
			         (mapping (babel-encodings:lookup-mapping babel:*string-vector-mappings* enc))
			         (counter (babel-encodings:code-point-counter mapping))
			         (decoder (babel-encodings:decoder mapping))
			         (end (length octets))
			         (pos 0)
			         (steps nil))
			    (do ()
			        ((>= pos end) (nreverse steps))
			      (multiple-value-bind (chars new-end) (funcall counter octets pos end 1)
			        (let ((string (make-string 1 :element-type 'babel:unicode-char)))
			          (funcall decoder octets pos new-end string 0)
			          (push (list chars pos new-end (aref string 0)) steps)
			          (setq pos new-end))))))
			(print (babel:octets-to-string (babel:string-to-octets "aあ🎉zé")))
			(print (decode-incrementally (babel:string-to-octets "aあ🎉zé") :utf-8))
			(print (decode-incrementally (octs '(97 233 122)) :latin-1))
			(print (list (babel-encodings:enc-max-units-per-char :utf-8)
			             (babel-encodings:enc-max-units-per-char :latin-1)
			             (babel-encodings:enc-max-units-per-char :us-ascii)))
			(print (list (babel-encodings:lookup-mapping babel:*string-vector-mappings* :utf8)
			             (babel-encodings:get-character-encoding :iso-8859-1)
			             babel:*string-vector-mappings*
			             (typep #\\a 'babel:unicode-char)))
			;; a truncated multi-byte sequence: the counter signals, and its condition is
			;; the one a consumer's fallback path catches
			(print (handler-case (decode-incrementally (octs '(#xE3 #x81)) :utf-8)
			         (babel-encodings:end-of-input-in-character (e)
			           (list :end-of-input (format nil "~A" e)))
			         (babel:character-decoding-error (e) (list :parent (format nil "~A" e)))))
			(print (handler-case (babel:octets-to-string (octs '(#xFF #x41)))
			         (babel-encodings:invalid-utf8-continuation-byte (e)
			           (list :bad-continuation
			                 (format nil "~A" e)
			                 (babel-encodings:character-coding-error-position e)
			                 (babel-encodings:character-decoding-error-octets e)))))
			(print (handler-case (babel:string-to-octets "あ" :encoding :us-ascii)
			         (babel:character-encoding-error (e)
			           (list :not-encodable
			                 (format nil "~A" e)
			                 (babel-encodings:character-encoding-error-code e)))))
			;; the special the consumer binds instead of passing :errorp
			(print (let ((babel-encodings:*suppress-character-coding-errors* t))
			         (list (map 'list #'char-code (babel:octets-to-string (octs '(#xE3 #x81))))
			               (map 'list #'char-code (babel:octets-to-string (octs '(#xFF #x41))))
			               (babel:string-to-octets "aあ" :encoding :latin-1))))
			(print (map 'list #'char-code (babel:octets-to-string (octs '(#xFF #x41)) :errorp nil)))
			;; the encoding half of the same protocol
			(let* ((mapping (babel-encodings:lookup-mapping babel:*string-vector-mappings* :utf-8))
			       (counter (babel-encodings:octet-counter mapping))
			       (encoder (babel-encodings:encoder mapping)))
			  (multiple-value-bind (size new-end) (funcall counter "aあb" 0 3 -1)
			    (let ((out (make-array size :element-type '(unsigned-byte 8))))
			      (print (list size new-end (funcall encoder "aあb" 0 3 out 0) out))))
			  (multiple-value-bind (size new-end) (funcall counter "aあb" 0 3 3)
			    (print (list size new-end))))
			(print (list (babel:string-size-in-octets "aあb")
			             (babel:string-size-in-octets "aあb" :max 3)
			             (babel:string-size-in-octets "aあb" :start 1 :end 2)))
			(print (handler-case (babel:string-to-octets "x" :encoding :cp932)
			         (error (e) (format nil "~A" e))))
			""";

	private static final List<String> EXPECTED = List.of("\"aあ🎉zé\"",
			"((1 0 1 #\\a) (1 1 4 #\\あ) (1 4 8 #\\🎉) (1 8 9 #\\z) (1 9 11 #\\é))",
			"((1 0 1 #\\a) (1 1 2 #\\é) (1 2 3 #\\z))", "(4 1 1)", "(:UTF-8 :LATIN-1 (:UTF-8 :LATIN-1 :US-ASCII) T)",
			"(:END-OF-INPUT \"Illegal :UTF-8 character starting at position 0.\")",
			"(:BAD-CONTINUATION \"Illegal :UTF-8 character starting at position 0.\" 0 (255))",
			"(:NOT-ENCODABLE \"Unable to encode character code point 12354 as :US-ASCII.\" 12354)",
			"((65533) (65533 65) #(97 26))", "(65533 65)", "(5 3 5 #(97 227 129 130 98))", "(1 1)", "(5 1 3)",
			"\"babel: unsupported character encoding :CP932 (this build implements (:UTF-8 :LATIN-1 :US-ASCII))\"");

	@Override
	protected String systemDir() {
		return SYSTEM_DIR;
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
		return "BabelMappingE2e";
	}

}
