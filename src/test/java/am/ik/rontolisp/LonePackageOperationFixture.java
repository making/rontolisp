package am.ik.rontolisp;

import java.util.Map;

/**
 * Programs whose ONLY package operation is one of {@code delete-package}, {@code shadow},
 * {@code shadowing-import} or {@code unintern}. The prelude defuns behind them reach
 * helpers -- the {@code %baked-packages%} table, the {@code string<} the runtime
 * {@code list-all-packages} lowering sorts with -- that used to arrive only with some
 * OTHER package operation, so each program is compiled on its own. A designator naming no
 * package signals the same {@code package-error} everywhere. Shared by the backend
 * suites, so every backend is held to one expected text; the {@code ci-spec.yaml}
 * standalone {@code lone-*} cases pin the same programs on the native binary.
 */
public final class LonePackageOperationFixture {

	private LonePackageOperationFixture() {
	}

	/** Each program, mapped to the output it prints on every backend. */
	public static final Map<String, String> PROGRAMS = Map.of("""
			(print (handler-case (delete-package "CI-LONE-NOPE")
			         (package-error (c) (list (package-error-package c) (princ-to-string c)))))
			(print (if (member :cl (list-all-packages)) t nil))
			""", """
			(:CI-LONE-NOPE "DELETE-PACKAGE: no such package: CI-LONE-NOPE")
			T""", """
			(print (shadow 'ci-lone-foo))
			(print (handler-case (shadow 'ci-lone-foo "CI-LONE-NOPE")
			         (package-error (c) (list (package-error-package c) (princ-to-string c)))))
			""", """
			T
			(:CI-LONE-NOPE "SHADOW: no such package: CI-LONE-NOPE")""", """
			(print (shadowing-import 'ci-lone-foo))
			(print (handler-case (shadowing-import 'ci-lone-foo "CI-LONE-NOPE")
			         (package-error (c) (list (package-error-package c) (princ-to-string c)))))
			""", """
			T
			(:CI-LONE-NOPE "SHADOWING-IMPORT: no such package: CI-LONE-NOPE")""", """
			(print (unintern 'car))
			(print (handler-case (unintern 'car "CI-LONE-NOPE")
			         (package-error (c) (list (package-error-package c) (princ-to-string c)))))
			""", """
			NIL
			(:CI-LONE-NOPE "UNINTERN: no such package: CI-LONE-NOPE")""");

}
