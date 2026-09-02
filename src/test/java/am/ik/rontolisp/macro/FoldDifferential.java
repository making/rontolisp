package am.ik.rontolisp.macro;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The differential harness behind {@link PureBuiltinFolder}: one probe per shape the fold
 * claims, each run TWICE in the same program -- once with literal arguments, which folds,
 * and once with every argument hidden behind a function parameter, which cannot. The two
 * values have to be the same text on every backend, because the folded one was rendered
 * by Java at compile time and the other by the backend's own runtime.
 *
 * <p>
 * This class exists so the probe list is written ONCE and consumed by all four backends:
 * {@code LispEvaluatorTest} (the reference -- the interpreter never folds, so its two
 * columns are the same runtime twice), {@code JvmLispCompilerTest} and
 * {@code WasmLispCompilerIntegrationTest} in both its Preview 1 and its
 * {@code --component} shape. {@code PureBuiltinFolderTest} additionally asserts that
 * every probe's folded spelling really did reduce to a literal, so a passing differential
 * can never be vacuous, and that every table entry has at least one probe -- "an entry
 * with no row does not ship".
 */
public final class FoldDifferential {

	private FoldDifferential() {
	}

	/**
	 * One row: the table operator it covers, the call as written (which folds) and the
	 * same call with its arguments behind {@code %id} (which does not).
	 *
	 * @param operator the {@link PureBuiltinFolder} table name this row covers
	 * @param call the folding spelling
	 * @param control the non-folding spelling of the same call
	 */
	public record Probe(String operator, String call, String control) {
	}

	/** The probe list. Every {@link PureBuiltinFolder} table name must appear. */
	public static final List<Probe> PROBES = probes();

	/**
	 * The program both columns are run as: each probe prints its folded value and then
	 * its runtime value, one per line, so a mismatch is a plain line-pair comparison with
	 * no separator to escape.
	 * @return the Lisp source
	 */
	public static String program() {
		StringBuilder source = new StringBuilder("(defun %id (x) x)\n");
		for (Probe probe : PROBES) {
			source.append("(prin1 ").append(probe.call()).append(") (terpri)\n");
			source.append("(prin1 ").append(probe.control()).append(") (terpri)\n");
		}
		return source.toString();
	}

	/**
	 * Asserts that the output of {@link #program()} has the folded and the runtime value
	 * of every probe, and that they agree.
	 * @param output the program's standard output
	 */
	public static void assertNoDivergence(String output) {
		List<String> lines = new ArrayList<>(List.of(output.split("\n", -1)));
		while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}
		assertThat(lines).as("one line per column, two per probe").hasSize(PROBES.size() * 2);
		for (int i = 0; i < PROBES.size(); i++) {
			Probe probe = PROBES.get(i);
			assertThat(lines.get(2 * i))
				.as("%s: folded %s vs runtime %s", probe.operator(), probe.call(), probe.control())
				.isEqualTo(lines.get(2 * i + 1));
		}
	}

	private static List<Probe> probes() {
		List<Probe> probes = new ArrayList<>();
		// -- exact integer arithmetic ------------------------------------------
		add(probes, "+", "1", "2");
		add(probes, "+", "1", "2", "3", "4");
		// The fixnum boundary: the sum promotes to a bignum on every backend.
		add(probes, "+", "9223372036854775807", "1");
		add(probes, "+", "-5", "2");
		add(probes, "*", "6", "7");
		add(probes, "*", "123456789012345", "1000000");
		add(probes, "-", "10", "3");
		add(probes, "-", "5");
		add(probes, "-", "1", "2", "3");
		add(probes, "/", "100", "5");
		add(probes, "/", "-12", "4");
		add(probes, "1+", "41");
		add(probes, "1-", "43");
		add(probes, "abs", "-7");
		add(probes, "signum", "-9");
		add(probes, "signum", "0");
		add(probes, "isqrt", "1000000");
		add(probes, "isqrt", "10");
		add(probes, "min", "3", "1", "2");
		add(probes, "max", "3", "1", "2");
		add(probes, "gcd", "12", "18");
		add(probes, "gcd", "-12", "18");
		add(probes, "lcm", "4", "6");
		// mod follows the divisor's sign and rem the dividend's: both signs, both ways.
		add(probes, "mod", "7", "3");
		add(probes, "mod", "-7", "3");
		add(probes, "mod", "7", "-3");
		add(probes, "rem", "7", "3");
		add(probes, "rem", "-7", "3");
		add(probes, "rem", "7", "-3");
		add(probes, "expt", "2", "10");
		add(probes, "expt", "2", "100");
		add(probes, "expt", "-3", "3");
		// -- bitwise ------------------------------------------------------------
		add(probes, "logand", "12", "10");
		add(probes, "logand", "-1", "255");
		add(probes, "logior", "12", "10");
		add(probes, "logxor", "12", "10");
		add(probes, "lognot", "5");
		add(probes, "ash", "1", "8");
		add(probes, "ash", "256", "-4");
		add(probes, "ash", "-8", "-1");
		add(probes, "integer-length", "255");
		add(probes, "integer-length", "-256");
		add(probes, "logbitp", "2", "12");
		add(probes, "logbitp", "0", "12");
		// -- numeric comparison and predicates ----------------------------------
		add(probes, "=", "2", "2");
		add(probes, "=", "2", "3");
		add(probes, "=", "1", "1", "1");
		add(probes, "<", "1", "2");
		add(probes, "<", "2", "1");
		add(probes, "<", "1", "2", "3");
		add(probes, ">", "2", "1");
		add(probes, "<=", "1", "1");
		add(probes, ">=", "1", "2");
		add(probes, "/=", "1", "2");
		add(probes, "/=", "1", "1");
		add(probes, "zerop", "0");
		add(probes, "zerop", "1");
		add(probes, "plusp", "1");
		add(probes, "plusp", "-1");
		add(probes, "minusp", "-1");
		add(probes, "evenp", "4");
		add(probes, "evenp", "3");
		add(probes, "oddp", "3");
		// -- characters ---------------------------------------------------------
		add(probes, "char-code", "#\\A");
		add(probes, "code-char", "66");
		add(probes, "char-upcase", "#\\a");
		add(probes, "char-upcase", "#\\1");
		add(probes, "char-downcase", "#\\Z");
		// Case conversion is NOT restricted to ASCII: every backend routes it through
		// the same Character.toUpperCase/toLowerCase mapping (the generated
		// wasm table included). Latin-1, Greek, Cyrillic and a supplementary-plane
		// pair (Deseret, U+10428) each exercise a different arm of it.
		add(probes, "char-upcase", "(code-char 233)");
		add(probes, "char-downcase", "(code-char 201)");
		add(probes, "char-upcase", "(code-char 945)");
		add(probes, "char-downcase", "(code-char 1040)");
		add(probes, "char-upcase", "(code-char 66600)");
		add(probes, "char=", "#\\a", "#\\a");
		add(probes, "char=", "#\\a", "#\\b");
		add(probes, "char<", "#\\a", "#\\b");
		add(probes, "char>", "#\\a", "#\\b");
		add(probes, "char<=", "#\\a", "#\\a");
		add(probes, "char>=", "#\\a", "#\\b");
		// -- string and list measurement ----------------------------------------
		add(probes, "length", "\"abc\"");
		add(probes, "length", "\"\"");
		add(probes, "length", "'(1 2 3)");
		add(probes, "length", "'()");
		add(probes, "char", "\"abc\"", "1");
		add(probes, "schar", "\"abc\"", "2");
		add(probes, "string=", "\"ab\"", "\"ab\"");
		add(probes, "string=", "\"ab\"", "\"ac\"");
		add(probes, "nth", "1", "'(a b c)");
		add(probes, "nth", "5", "'(a b)");
		add(probes, "car", "'(a b)");
		add(probes, "car", "'()");
		add(probes, "first", "'(1 2)");
		add(probes, "second", "'(1 2)");
		add(probes, "third", "'(1 2 3)");
		// -- string production --------------------------------------------------
		add(probes, "symbol-name", "'foo");
		add(probes, "symbol-name", ":bar");
		add(probes, "princ-to-string", "42");
		add(probes, "princ-to-string", "\"hi\"");
		add(probes, "princ-to-string", "#\\a");
		add(probes, "princ-to-string", "nil");
		add(probes, "princ-to-string", "t");
		// Floats fold: every backend prints the same Schubfach
		// shortest round-trip decimal, lowercase exponent marker included.
		add(probes, "princ-to-string", "1.21");
		add(probes, "princ-to-string", "1.0e10");
		add(probes, "princ-to-string", "-0.0");
		add(probes, "princ-to-string", "4.9e-324");
		add(probes, "prin1-to-string", "\"hi\"");
		add(probes, "prin1-to-string", "#\\a");
		add(probes, "prin1-to-string", "42");
		add(probes, "prin1-to-string", "3.14159");
		// The expander's internal piece conversions: the same rendering as the two
		// public names, folded to a PLAIN literal (a piece never reaches the program,
		// so it needs no fresh copy), while the public names fold to the fresh-string
		// constant below.
		add(probes, "%princ-piece", "42");
		add(probes, "%princ-piece", "\"hi\"");
		add(probes, "%princ-piece", "nil");
		add(probes, "%princ-piece", "1.21");
		add(probes, "%prin1-piece", "\"hi\"");
		add(probes, "%prin1-piece", "#\\a");
		// CL folds character by character, so no mapping changes the length -- and the
		// mapping is the full Unicode one on every backend, supplementary planes
		// included. These four are the FRESH-STRING producers: they fold to a
		// (%str-fresh ...) constant that materializes a fresh MUTABLE string per
		// evaluation, and each probe checks the folded copy against the runtime
		// producer's result (the string-identity-cross-backend ci-spec case pins the
		// identity half).
		add(probes, "string-upcase", "\"hello\"");
		add(probes, "string-downcase", "\"HELLO\"");
		add(probes, "string-upcase", "\"caf\u00e9 stra\u00dfe \u03b1\u03b2\u03b3\"");
		add(probes, "string-downcase", "\"CAF\u00c9 \u0399\u03a3\u03a9 \u0410\u0411\"");
		// concatenate's RESULT TYPE stays literal in the control: the compile-path
		// lowering resolves the designator statically, so hiding it would not be the
		// same call (.kb/concatenate-result-families.md).
		probes.add(new Probe("concatenate", "(concatenate 'string \"ab\" \"cd\")",
				"(concatenate 'string (%id \"ab\") (%id \"cd\"))"));
		add(probes, "subseq", "\"hello\"", "1", "3");
		add(probes, "subseq", "\"hello\"", "2");
		// The packed literal table. Like concatenate's, the RESULT TYPE stays literal in
		// the control -- the compile-path lowering resolves the designator statically, so
		// hiding it would not be the same call. Every width, and one table long enough to
		// cross the wasm backend's data-segment threshold (16 elements), so both
		// emissions are compared against the runtime build.
		probes.add(new Probe("coerce", "(coerce '(1 2 3) '(vector (unsigned-byte 8)))",
				"(coerce (%id '(1 2 3)) '(vector (unsigned-byte 8)))"));
		probes.add(new Probe("coerce", "(coerce #(0 65535 7) '(simple-array (unsigned-byte 16) (*)))",
				"(coerce (%id #(0 65535 7)) '(simple-array (unsigned-byte 16) (*)))"));
		probes.add(new Probe("coerce", "(coerce '(0 4294967295 2147483648) '(vector (unsigned-byte 32)))",
				"(coerce (%id '(0 4294967295 2147483648)) '(vector (unsigned-byte 32)))"));
		probes.add(new Probe("coerce",
				"(coerce '(0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17) '(vector (unsigned-byte 8)))",
				"(coerce (%id '(0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17)) '(vector (unsigned-byte 8)))"));
		probes.add(new Probe("coerce",
				"(coerce '(0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 4294967295) '(vector (unsigned-byte 32)))",
				"(coerce (%id '(0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 4294967295)) "
						+ "'(vector (unsigned-byte 32)))"));
		probes.add(new Probe("make-array",
				"(make-array 3 :element-type '(unsigned-byte 16) :initial-contents '(9 8 65535))",
				"(make-array (%id 3) :element-type '(unsigned-byte 16) :initial-contents (%id '(9 8 65535)))"));
		return List.copyOf(probes);
	}

	/** The operators covered, for the "every table entry has a row" assertion. */
	public static Set<String> coveredOperators() {
		Set<String> covered = new LinkedHashSet<>();
		for (Probe probe : PROBES) {
			covered.add(probe.operator().toUpperCase(java.util.Locale.ROOT));
		}
		return covered;
	}

	private static void add(List<Probe> probes, String operator, String... args) {
		StringBuilder call = new StringBuilder("(").append(operator);
		StringBuilder control = new StringBuilder("(").append(operator);
		for (String arg : args) {
			call.append(' ').append(arg);
			control.append(" (%id ").append(arg).append(')');
		}
		probes.add(new Probe(operator, call.append(')').toString(), control.append(')').toString()));
	}

}
