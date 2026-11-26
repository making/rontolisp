package am.ik.rontolisp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Programs carrying a string constant past the 65,535 bytes one JVM {@code CONSTANT_Utf8}
 * holds: a long literal (evaluated as a form, as a {@code format} control and inside
 * quoted data), a {@code defpackage} whose own exports pack past it, and one whose
 * {@code :use} list makes the accessible universe pack past it. The literal straddles the
 * cut with a two-, a three- and a six-byte (supplementary) character, so a piece boundary
 * falls among them. Shared by the backend suites, so every backend is held to one
 * expected text (SBCL's output).
 */
public final class LongStringConstantFixture {

	private LongStringConstantFixture() {
	}

	/** Each program, mapped to the output it prints on every backend. */
	public static final Map<String, String> PROGRAMS = programs();

	private static Map<String, String> programs() {
		Map<String, String> programs = new LinkedHashMap<>();
		String literal = "a".repeat(65530) + "éあ" + new String(Character.toChars(0x1F600)) + "あ" + "b".repeat(4460);
		programs.put("""
				(defvar *s* "%1$s")
				(defun lit () "%1$s")
				(print (length *s*))
				(print (list (char *s* 65529) (char-code (char *s* 65530)) (char-code (char *s* 65531))
				             (char-code (char *s* 65532)) (char-code (char *s* 65533)) (char *s* 69993)))
				(print (list (eq (lit) (lit)) (string= *s* (lit))))
				(print (let ((n 0))
				         (dotimes (i (length *s*)) (setq n (mod (+ (* n 31) (char-code (char *s* i))) 1000000007)))
				         n))
				(print (length (format nil "%1$s~D" 42)))
				(print (length (second '(1 "%1$s"))))
				""".formatted(literal), """
				69994
				(#\\a 233 12354 128512 12354 #\\b)
				(T T)
				295350532
				69996
				69994""");
		StringBuilder wide = new StringBuilder("(defpackage :lsc-wide (:use) (:export");
		for (int i = 0; i < 4800; i++) {
			wide.append(" #:WIDE-SYMBOL-").append("%05d".formatted(i));
		}
		wide.append("))\n").append("""
				(print (length (let ((r nil)) (do-external-symbols (s :lsc-wide) (push s r)) r)))
				(print (symbol-name (find-symbol "WIDE-SYMBOL-04799" :lsc-wide)))
				(print (nth-value 1 (find-symbol "WIDE-SYMBOL-00000" :lsc-wide)))
				""");
		programs.put(wide.toString(), """
				4800
				"WIDE-SYMBOL-04799"
				:EXTERNAL""");
		StringBuilder uses = new StringBuilder();
		for (int p = 0; p < 4; p++) {
			uses.append("(defpackage :lsc-u").append(p).append(" (:use) (:export");
			for (int i = 0; i < 4000; i++) {
				uses.append(" #:").append((char) ('A' + p)).append(i);
			}
			uses.append("))\n");
		}
		uses.append("""
				(defpackage :lsc-all (:use :lsc-u0 :lsc-u1 :lsc-u2 :lsc-u3))
				(print (length (let ((r nil)) (do-symbols (s :lsc-all) (push s r)) r)))
				(print (symbol-name (find-symbol "D3999" :lsc-all)))
				""");
		programs.put(uses.toString(), """
				16000
				"D3999\"""");
		return programs;
	}

}
