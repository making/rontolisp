package am.ik.rontolisp.testsupport;

/**
 * The {@code %mask-signed-field} program pinned identically on the JVM and both WASM
 * backends, at the default level (fused) and the size level (the lowering alone).
 */
public final class MaskSignedFieldProgram {

	private MaskSignedFieldProgram() {
	}

	/**
	 * {@code %mask-signed-field} over the shapes the fused root takes (a product, a sum,
	 * a narrower field, a raw local's step) and the bails it survives (a bignum leaf, a
	 * float, nil), plus literal fields the fusion leaves to the lowering (100 and 0 bits,
	 * a lone constant). The values are SBCL's {@code sb-c::mask-signed-field}.
	 */
	public static final String PROGRAM = """
			(defun msf-mul (a b) (%mask-signed-field 64 (* a b)))
			(defun msf-add (a b) (%mask-signed-field 64 (+ a b)))
			(defun msf-int (a b) (%mask-signed-field 32 (* a b)))
			(defun msf-byte (x) (%mask-signed-field 8 x))
			(print (msf-mul 9223372036854775807 31))
			(print (msf-mul 3 4))
			(print (msf-mul -9223372036854775808 -1))
			(print (msf-add 9223372036854775807 1))
			(print (msf-add -5 3))
			(print (msf-mul 18446744073709551617 3))
			(print (msf-mul (+ (expt 2 100) 12345) (+ (expt 2 70) 5)))
			(print (msf-mul -18446744073709551619 7))
			(print (msf-int 65536 65536))
			(print (msf-int 46341 46341))
			(print (list (msf-byte 255) (msf-byte 128) (msf-byte 127) (msf-byte -129) (msf-byte 0)))
			(print (list (%mask-signed-field 1 1) (%mask-signed-field 1 2) (%mask-signed-field 0 12345)))
			(print (%mask-signed-field 63 (ash 1 62)))
			(print (%mask-signed-field 64 (ash 1 63)))
			(print (%mask-signed-field 64 (- (expt 2 64) 1)))
			(print (%mask-signed-field 100 (expt 2 99)))
			(print (%mask-signed-field 64 (+ (* 3037000500 3037000500) (ash (msf-mul 7 9) 2))))
			(let ((h 1125899906842597))
			  (dotimes (i 20) (setq h (%mask-signed-field 64 (+ (* h 31) i))))
			  (print h))
			(print (handler-case (%mask-signed-field 64 1.5) (type-error (c) (princ-to-string c))))
			(print (handler-case (msf-mul 1.5 2) (type-error (c) (princ-to-string c))))
			(print (handler-case (msf-add nil 2) (type-error () :type-error)))
			""";

	/** What {@link #PROGRAM} prints. */
	public static final String EXPECTED = """
			9223372036854775777
			12
			-9223372036854775808
			-9223372036854775808
			-2
			3
			61725
			-21
			0
			-2147479015
			(-1 -128 127 127 0)
			(-1 0 0)
			-4611686018427387904
			-9223372036854775808
			-1
			-633825300114114700748351602688
			-9223372036709301364
			-6598722044477124753
			"LOGXOR: The value 1.5 is not of type INTEGER"
			"LOGXOR: The value 3.0 is not of type INTEGER"
			:TYPE-ERROR""";

}
