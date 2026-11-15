package am.ik.rontolisp.starlark;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.eval.StarlarkLibrary;
import am.ik.rontolisp.reader.LispReadException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The lowering table of {@code .kb/starlark-frontend.md}, row by row, as the Common Lisp
 * the front end emits. What the emitted forms DO on each backend is
 * {@code StarlarkSpecE2eTest}'s business.
 */
class StarlarkLoweringTest {

	private static final String RT = "RONTOLISP::%STARLARK-";

	/** The files a {@code load} statement of {@code t.star} finds. */
	private static final Map<String, String> FILES = Map.of("m.star", """
			x = 1
			_y = 2
			def f(a, b):
			    return a + b
			if x:
			    late = 2
			""", "cycle.star", "load(\"t.star\", \"x\")\n", "reexport.star", "load(\"m.star\", \"x\")\n");

	// t.star itself is there too: cycle.star loads it back.
	private static final StarlarkFiles BESIDE = (from, path) -> FILES.containsKey(path) || path.equals("t.star")
			? new StarlarkFiles.Source(path, FILES.getOrDefault(path, "")) : null;

	// A source whose first line is "# spec" is read against --starlark-standard spec.
	private static StarlarkStandard standard(String source) {
		return source.startsWith("# spec\n") ? StarlarkStandard.SPEC : StarlarkStandard.RONTOLISP;
	}

	// The two forms bracketing the file's run are in every program: one row shows them.
	private static String lowered(String source) {
		List<String> forms = Starlark.read(source, "t.star", standard(source), BESIDE)
			.stream()
			.map(LispVal::print)
			.toList();
		String begin = "(SETQ # (" + RT + "FILE-BEGIN))";
		String end = "(" + RT + "FILE-END #)";
		assertThat(forms).contains(begin.replace("#", FILE)).last().isEqualTo(end.replace("#", FILE));
		boolean bracketed = source.startsWith("load(");
		return forms.stream()
			.filter(form -> bracketed || !form.equals(begin.replace("#", FILE)) && !form.equals(end.replace("#", FILE)))
			.collect(Collectors.joining("\n"));
	}

	/** The variable holding the state of {@code t.star}'s run. */
	private static final String FILE = "|sl%(t.star)%SL-FILE|";

	// $ stands for the run-time helpers' prefix, @ for the prefix of m.star's globals.
	static List<Arguments> rows() {
		return List.of(
				// values, and an identifier with no lowercase letter
				arguments("T = [None, True, False, 1, -2.5, \"s\", (1,), {\"k\": 1}]\nt = T\n", """
						(SETQ |s%T| ($MAKE-LIST :NONE T NIL 1 -2.5 "s" ($MAKE-TUPLE 1) ($MAKE-DICT "k" 1)))
						(SETQ |t| |s%T|)"""),
				// a top-level def bound once: a defun ahead of every statement, entered
				// directly by a call that fills each parameter by position; above the
				// def, the call checks the global the def assigns
				arguments("f(1, 2)\ndef f(a, b = 2):\n    return a\nf(1)\ng = f\n", """
						(DEFUN |f| (|a| |b|) |a|)
						(SETQ |f| :%UNBOUND)
						(PROGN ($GLOBAL |f| "f") (|f| 1 2))
						(SETQ |f| ($MAKE-FUNCTION "f" '("a" "b") (LIST :%REQUIRED 2) 2 NIL NIL #'|f|))
						($CALL |f| (LIST 1) NIL)
						(SETQ |g| |f|)"""),
				// any other function is a value over a lambda taking the parameters, then
				// the surplus positional arguments, then the surplus named ones
				arguments("def f(a, *args, c = 1, **kw):\n    pass\n",
						"""
								(SETQ |f| ($MAKE-FUNCTION "f" '("a" "c") (LIST :%REQUIRED 1) 1 T T (LAMBDA (|a| |c| |args| |kw|) :NONE)))"""),
				// a body: a block around a let of every assigned name, unassigned until
				// assigned and read through a check where a path may not assign it; a
				// loop: a tagbody over the iterable's vector, given back before a return
				// leaves it
				arguments("""
						def f(xs):
						    for i, x in xs:
						        if x:
						            return i
						        elif i:
						            continue
						        break
						    n = x
						""",
						"""
								(DEFUN |f| (|xs|) (BLOCK %SL-FN (LET ((|i| :%UNBOUND) (|x| :%UNBOUND) (|n| :%UNBOUND)) \
								(LET* ((%SL-SEQ1 |xs|) (%SL-ITEMS2 ($ITERATE %SL-SEQ1)) (%SL-COUNT3 (LENGTH %SL-ITEMS2)) (%SL-INDEX4 0)) \
								(BLOCK %SL-BREAK (TAGBODY %SL-TOP5 (IF (< %SL-INDEX4 %SL-COUNT3) (PROGN \
								(LET ((%SL-UNPACKED6 ($UNPACK (AREF %SL-ITEMS2 %SL-INDEX4) 2))) \
								(SETQ |i| (AREF %SL-UNPACKED6 0)) (SETQ |x| (AREF %SL-UNPACKED6 1))) \
								(SETQ %SL-INDEX4 (+ %SL-INDEX4 1)) \
								(BLOCK %SL-CONTINUE (IF ($TRUTH |x|) (PROGN ($ITERATED %SL-SEQ1) (RETURN-FROM %SL-FN |i|)) \
								(IF ($TRUTH |i|) (RETURN-FROM %SL-CONTINUE NIL))) (RETURN-FROM %SL-BREAK NIL)) \
								(GO %SL-TOP5))))) ($ITERATED %SL-SEQ1)) (SETQ |n| ($LOCAL |x| "x")) :NONE)))
								(SETQ |f| ($MAKE-FUNCTION "f" '("xs") NIL 1 NIL NIL #'|f|))"""),
				// truth is fused into a test; `and` / `or` as values keep the operand
				arguments(
						"def f(a, b, c):\n    if a == b and not c or b:\n        return a or b\n    return a == 1 and b\n",
						"""
								(DEFUN |f| (|a| |b| |c|) (BLOCK %SL-FN \
								(IF (OR (AND ($EQUAL |a| |b|) (NOT ($TRUTH |c|))) ($TRUTH |b|)) \
								(RETURN-FROM %SL-FN (LET ((%SL-VALUE1 |a|)) (IF ($TRUTH %SL-VALUE1) %SL-VALUE1 |b|)))) \
								(IF ($EQUAL |a| 1) |b| NIL)))
								(SETQ |f| ($MAKE-FUNCTION "f" '("a" "b" "c") NIL 3 NIL NIL #'|f|))"""),
				// a comprehension: the same loop pushing onto a vector, its variable its
				// own
				arguments("x = [v * 2 for v in range(3) if v]\n",
						"""
								(SETQ |x| (LET ((%SL-ACC1 ($VECTOR)) (|v| :%UNBOUND)) \
								(LET* ((%SL-SEQ2 ($B-RANGE 3 :%UNSET 1)) (%SL-ITEMS3 ($ITERATE %SL-SEQ2)) (%SL-COUNT4 (LENGTH %SL-ITEMS3)) (%SL-INDEX5 0)) \
								(TAGBODY %SL-TOP6 (IF (< %SL-INDEX5 %SL-COUNT4) (PROGN (SETQ |v| (AREF %SL-ITEMS3 %SL-INDEX5)) \
								(SETQ %SL-INDEX5 (+ %SL-INDEX5 1)) (IF ($TRUTH |v|) (VECTOR-PUSH-EXTEND ($MUL |v| 2) %SL-ACC1)) \
								(GO %SL-TOP6)))) ($ITERATED %SL-SEQ2)) ($LIST-FROM %SL-ACC1)))"""),
				// a built-in: bound while lowering, defaults filled, the arguments still
				// evaluated as written; one that does not fit goes to the run-time
				// binder,
				// as a value
				arguments("def f(xs, k, r):\n    return sorted(xs, reverse = r(), key = k()), len(*xs)\n",
						"""
								(DEFUN |f| (|xs| |k| |r|) ($MAKE-TUPLE \
								(LET* ((%SL-ARG1 ($CALL |r| NIL NIL)) (%SL-ARG2 ($CALL |k| NIL NIL))) ($B-SORTED |xs| %SL-ARG2 %SL-ARG1)) \
								($CALL ($BUILTIN "len" '("x") NIL 1 1 NIL NIL #'$B-LEN) ($STAR-ELEMENTS |xs|) NIL)))
								(SETQ |f| ($MAKE-FUNCTION "f" '("xs" "k" "r") NIL 3 NIL NIL #'|f|))"""),
				// a method: its helper, the receiver first; a name no type has is refused
				// before its arguments are evaluated; uncalled, it is bound to its
				// receiver
				arguments("def f(x, g):\n    x.update(g(), a = 1)\n    x.nope(g())\n    return x.append\n",
						"""
								(DEFUN |f| (|x| |g|) ($M-UPDATE |x| ($CALL |g| NIL NIL) ($MAKE-DICT "a" 1)) ($NO-ATTRIBUTE |x| "nope") \
								($METHOD |x| '("list") "append" '("x") NIL 1 1 NIL NIL #'$M-APPEND))
								(SETQ |f| ($MAKE-FUNCTION "f" '("x" "g") NIL 2 NIL NIL #'|f|))"""),
				// assignment: the value before the target's operands; an augmented one
				// evaluates the target's operands once
				arguments("def f(a, g, i):\n    a[g()] = g()\n    a[i], i = i, a\n    a[g()] -= 1\n    i += [1]\n",
						"""
								(DEFUN |f| (|a| |g| |i|) \
								(LET* ((%SL-ARG1 ($CALL |g| NIL NIL)) (%SL-ARG2 ($CALL |g| NIL NIL))) ($SET-INDEX |a| %SL-ARG2 %SL-ARG1)) \
								(LET ((%SL-UNPACKED3 ($UNPACK ($MAKE-TUPLE |i| |a|) 2))) \
								($SET-INDEX |a| |i| (AREF %SL-UNPACKED3 0)) (SETQ |i| (AREF %SL-UNPACKED3 1))) \
								(LET* ((%SL-KEY4 ($CALL |g| NIL NIL))) ($SET-INDEX |a| %SL-KEY4 ($SUB ($INDEX |a| %SL-KEY4) 1))) \
								(SETQ |i| ($ADD-IN-PLACE |i| ($MAKE-LIST 1))) :NONE)
								(SETQ |f| ($MAKE-FUNCTION "f" '("a" "g" "i") NIL 3 NIL NIL #'|f|))"""),
				// --starlark-standard spec: a call while a call of the same declaration
				// runs is refused, by a flag of the declaration's own, spelled behind
				// the file's prefix
				arguments("# spec\ndef f(n):\n    return f(n - 1)\n", """
						(DEFUN |f| (|n|) ($ENTER |sl%(t.star)%SL-ACTIVE1| "f") \
						(LET ((%SL-RESULT2 (|f| ($SUB |n| 1)))) ($LEAVE |sl%(t.star)%SL-ACTIVE1|) %SL-RESULT2))
						(SETQ |sl%(t.star)%SL-ACTIVE1| (LIST NIL))
						(SETQ |f| ($MAKE-FUNCTION "f" '("n") NIL 1 NIL NIL #'|f|))"""),
				// load: the module's definitions with the program's, its globals behind
				// its path; its statements where the first load of it stands, behind a
				// state that runs them once and between the two forms that bracket a
				// file's run; what the statement binds is a global of the loading file,
				// read through a check where a path of the module may not assign it. A
				// name bound by the load alone is entered directly. @package is a Common
				// Lisp package: the symbol's function, called as it stands.
				arguments("""
						load("m.star", "f", y = "x", "late")
						load("@cl", "gcd", up = "string-upcase")
						def g(n):
						    return f(n, y) + gcd(n, 4) + late
						""", """
						(DEFUN |@f| (|a| |b|) ($ADD |a| |b|))
						(DEFVAR |@%SL-FILE| NIL)
						(DEFUN |g| (|n|) ($ADD ($ADD (|@f| |n| |y|) (CL::GCD |n| 4)) |late|))
						(SETQ |sl%(t.star)%SL-FILE| ($FILE-BEGIN))
						(SETQ |@%SL-FILE| (IF |@%SL-FILE| T ($FILE-BEGIN)))
						(IF (CONSP |@%SL-FILE|) (SETQ |@late| :%UNBOUND))
						(IF (CONSP |@%SL-FILE|) (SETQ |@x| 1))
						(IF (CONSP |@%SL-FILE|) (SETQ |@_y| 2))
						(IF (CONSP |@%SL-FILE|) (SETQ |@f| ($MAKE-FUNCTION "f" '("a" "b") NIL 2 NIL NIL #'|@f|)))
						(IF (CONSP |@%SL-FILE|) (IF ($TRUTH |@x|) (SETQ |@late| 2)))
						(IF (CONSP |@%SL-FILE|) (SETQ |@%SL-FILE| ($FILE-END |@%SL-FILE|)))
						(SETQ |f| |@f|)
						(SETQ |y| |@x|)
						(SETQ |late| ($LOADED |@late| "late" "m.star"))
						(SETQ |gcd| ($FOREIGN "cl:gcd" #'CL::GCD))
						(SETQ |up| ($FOREIGN "cl:string-upcase" #'CL::STRING-UPCASE))
						(SETQ |g| ($MAKE-FUNCTION "g" '("n") NIL 1 NIL NIL #'|g|))
						($FILE-END |sl%(t.star)%SL-FILE|)"""));
	}

	@ParameterizedTest
	@MethodSource("rows")
	void aConstructLowersToItsRowOfTheTable(String source, String forms) {
		assertThat(lowered(source)).isEqualTo(forms.replace("$", RT).replace("@", "sl%(m.star)"));
	}

	static List<Arguments> refusals() {
		return List.of(arguments("x = 1\nprint(nope)\n", "t.star:2:7: undefined: nope"),
				arguments("def f():\n    return g()\n", "t.star:2:12: undefined: g"),
				arguments("x = b\"abc\"\n", "t.star:1:5: bytes literals are not supported yet"),
				// load: a file that is not there, a name the module keeps or does not
				// define -- what it loaded itself is not its own -- a file loading the
				// one that loads it, a name no identifier can bind, a load that is not a
				// top-level statement
				arguments("load(\"nope.star\", \"x\")\n", "t.star:1:1: cannot load nope.star: file not found"),
				arguments("load(\"m.star\", \"_y\")\n",
						"t.star:1:16: load: names with leading underscores are not exported: _y"),
				arguments("load(\"m.star\", \"z\")\n", "t.star:1:16: load: name z not found in module m.star"),
				arguments("load(\"reexport.star\", \"x\")\n",
						"t.star:1:23: load: name x not found in module reexport.star"),
				arguments("load(\"cycle.star\", \"x\")\n", "cycle.star:1:1: cannot load t.star: cycle in load graph"),
				arguments("load(\"@cl\", \"string-upcase\")\n",
						"t.star:1:13: load: \"string-upcase\" is no identifier: write NAME = \"string-upcase\""),
				arguments("if True:\n    load(\"m.star\", \"x\")\n", "t.star:2:5: load statement within a conditional"),
				// --starlark-standard spec
				arguments("# spec\nx = 1\nif x:\n    pass\n", "t.star:3:1: if statement not within a function"),
				arguments("# spec\nfor x in []:\n    pass\n", "t.star:2:1: for loop not within a function"),
				arguments("# spec\nx, y = 1, 2\ndef y():\n    pass\n",
						"t.star:3:1: cannot reassign global y declared at t.star:2:4"),
				arguments("# spec\nload(\"m.star\", \"x\")\nx = 2\n",
						"t.star:3:1: cannot reassign local x declared at t.star:2:16"),
				arguments("# spec\nx = 2\nload(\"m.star\", \"x\")\n", "t.star:3:16: cannot reassign top-level x"));
	}

	@ParameterizedTest
	@MethodSource("refusals")
	void aNameNothingBindsAMissingConstructAndWhatTheSpecRefusesAreRefusedAtTheirPosition(String source,
			String message) {
		assertThatThrownBy(() -> Starlark.read(source, "t.star", standard(source), BESIDE))
			.isInstanceOf(LispReadException.class)
			.hasMessage(message);
	}

	/**
	 * The direct tier calls a helper with the parameters its signature declares, in that
	 * order: the helper {@code starlark.lisp} defines must take exactly those.
	 */
	@Test
	void everyBuiltinsHelperTakesTheParametersItsSignatureDeclares() {
		Map<String, Integer> arities = new HashMap<>();
		for (LispVal form : StarlarkLibrary.forms()) {
			if (form instanceof LispCons definition && definition.car() instanceof LispSymbol head
					&& head.name().equals("DEFUN")) {
				List<LispVal> parts = definition.toList();
				List<LispVal> parameters = parts.get(2) instanceof LispCons list ? list.toList() : List.of();
				boolean plain = parameters.stream()
					.allMatch(parameter -> parameter instanceof LispSymbol symbol && !symbol.name().startsWith("&"));
				arities.put(((LispSymbol) parts.get(1)).name(), plain ? parameters.size() : -1);
			}
		}
		assertThat(StarlarkBuiltins.signatures()).hasSizeGreaterThan(50).allSatisfy(signature -> {
			int expected = signature.params().size() + (signature.varargs() ? 1 : 0) + (signature.kwargs() ? 1 : 0)
					+ (signature.isMethod() ? 1 : 0);
			assertThat(arities).as("the helper of %s", signature.name()).containsEntry(signature.helper(), expected);
		});
	}

}
