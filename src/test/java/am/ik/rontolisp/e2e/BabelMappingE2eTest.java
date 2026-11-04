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
 * vector to hand to {@code octets-to-string} and drives exactly these five names (found
 * by the dexador spike). A driver-only shim answered
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
 *
 * <p>
 * The rest of the exercise covers the externals past that protocol, with the answers real
 * babel gives on SBCL 2.2.9: the {@code enc-*} readers, the size and encodability
 * functions, the bounds drivers check, external formats, and that
 * {@code *default-character-encoding*} is read when a call runs.
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
			(defun lst (v) (map 'list #'identity v))
			(dolist (name '(:utf-8 :latin-1 :us-ascii))
			  (let ((e (babel-encodings:get-character-encoding name)))
			    (print (list (babel-encodings:enc-name e)
			                 (babel-encodings:enc-aliases e)
			                 (babel-encodings:enc-code-unit-size e)
			                 (babel-encodings:enc-codespace e)
			                 (babel-encodings:enc-native-endianness e)
			                 (babel-encodings:enc-decode-literal-code-unit-limit e)
			                 (babel-encodings:enc-encode-literal-code-unit-limit e)
			                 (babel-encodings:enc-use-bom e)
			                 (and (babel-encodings:enc-bom-encoding e)
			                      (lst (babel-encodings:enc-bom-encoding e)))
			                 (lst (babel-encodings:enc-nul-encoding e))
			                 (babel-encodings:enc-default-replacement e)
			                 (babel-encodings:ambiguous-encoding-p e)
			                 (typep e 'babel-encodings:character-encoding)
			                 (babel-encodings:encodable-code-point-p e 128)
			                 (babel-encodings:encodable-code-point-p e 257)))))
			(print (lst (babel:concatenate-strings-to-octets :utf-8 "a" "あ" "")))
			(print (lst (babel:concatenate-strings-to-octets :latin-1 "é" "z")))
			(print (list (multiple-value-list (babel:vector-size-in-chars (octs '(97 227 129 130 98))))
			             (multiple-value-list (babel:vector-size-in-chars (octs '(97 227 129 130 98)) :max 2))
			             (multiple-value-list (babel:string-size-in-octets "aあb"))
			             (multiple-value-list (babel:string-size-in-octets "aあb" :max 3))))
			(print (list (handler-case (babel:string-to-octets "a" :start 2) (error (e) (format nil "~A" e)))
			             (handler-case (babel:octets-to-string (octs '(97)) :end 3) (error (e) (format nil "~A" e)))))
			(print (list (lst (babel:string-to-octets "a" :use-bom t)) (lst (babel:string-to-octets "a" :use-bom nil))))
			(print (list (babel:encodable-character-p #\\a)
			             (babel:encodable-character-p (code-char 233) :encoding :ascii)
			             (babel:encodable-string-p "abc" :encoding :ascii)
			             (multiple-value-list (babel:encodable-string-p "aéc" :encoding :ascii))
			             (babel:encodable-string-p "aéc" :encoding :ascii :end 1)
			             (= babel:unicode-char-code-limit char-code-limit)))
			(print (let ((babel:*default-character-encoding* :latin-1))
			         (list (lst (babel:string-to-octets "é"))
			               (babel:octets-to-string (octs '(233)))
			               (babel:encodable-character-p (code-char 300))
			               (babel-encodings:enc-name (babel-encodings:get-character-encoding :default)))))
			(let ((ef (babel:make-external-format :latin-1 :eol-style :crlf)))
			  (print (list (babel-encodings:enc-name (babel:external-format-encoding ef))
			               (babel:external-format-eol-style ef)
			               (babel:external-format-eol-style (babel:make-external-format :utf-8))
			               (typep ef 'babel:external-format)
			               (eq ef (babel:ensure-external-format ef))
			               (babel:external-format-equal ef (babel:ensure-external-format '(:latin1 :eol-style :crlf)))
			               (babel:external-format-equal ef (babel:ensure-external-format :latin-1))
			               (lst (babel:string-to-octets "é" :encoding ef)))))
			(print (handler-case (babel:make-external-format :utf-8 :eol-style :nope)
			         (type-error (e) (type-error-datum e))))
			(print (babel-encodings:with-checked-simple-vector ((v (octs '(1 2 3))) (s 1) (e nil))
			         (list (aref v s) e)))
			(print (handler-case (babel-encodings:with-checked-simple-vector ((v (octs '(1 2 3))) (s 2) (e 9))
			                       (list v s e))
			         (error (c) (format nil "~A" c))))
			;; rontolisp's own: the reader syntax it does not implement refuses by name
			(print (handler-case (babel:set-sharp-backslash-syntax-in-readtable)
			         (error (c) (format nil "~A" c))))
			""";

	private static final List<String> EXPECTED = List.of("\"aあ🎉zé\"",
			"((1 0 1 #\\a) (1 1 4 #\\あ) (1 4 8 #\\🎉) (1 8 9 #\\z) (1 9 11 #\\é))",
			"((1 0 1 #\\a) (1 1 2 #\\é) (1 2 3 #\\z))", "(4 1 1)", "(:UTF-8 :ISO-8859-1 (:UTF-8 :ISO-8859-1 :ASCII) T)",
			"(:END-OF-INPUT \"Illegal :UTF-8 character starting at position 0.\")",
			"(:BAD-CONTINUATION \"Illegal :UTF-8 character starting at position 0.\" 0 (255))",
			"(:NOT-ENCODABLE \"Unable to encode character code point 12354 as :ASCII.\" 12354)",
			"((65533) (65533 65) #(97 26))", "(65533 65)", "(5 3 5 #(97 227 129 130 98))", "(1 1)", "(5 1 3)",
			"\"babel: unsupported character encoding :CP932 (this build implements (:UTF-8 :ISO-8859-1 :ASCII))\"",
			"(:UTF-8 NIL 8 ((0 1114111)) T 128 128 NIL (239 187 191) (0) 65533 NIL T T T)",
			"(:ISO-8859-1 (:LATIN-1 :LATIN1) 8 ((0 256)) T 256 256 NIL NIL (0) 26 NIL T T NIL)",
			"(:ASCII (:US-ASCII) 8 ((0 127)) T 128 128 NIL NIL (0) 26 NIL T NIL NIL)", "(97 227 129 130)", "(233 122)",
			"((3 5) (2 4) (5 3) (1 1))",
			"(\"Invalid start (2) and end (1) values for vector of length 1.\" \"Invalid start (0) and end (3) values for vector of length 1.\")",
			"((239 187 191 97) (97))", "(T NIL T (NIL 1) T T)", "((233) \"é\" NIL :ISO-8859-1)",
			"(:ISO-8859-1 :CRLF :LF T T T NIL (233))", ":NOPE", "(2 3)",
			"\"Invalid start (2) and end (9) values for vector of length 3.\"",
			"\"babel: the #\\\\uXXXX reader syntax is not implemented\"");

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
