package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.LispReader;

/**
 * A small prelude of standard functions implemented once in rontolisp source, so a single
 * recursive definition runs on every backend instead of a hand-assembled runtime helper
 * per backend. Each entry is a self-contained {@code defun} using only primitives every
 * backend compiles; it is materialised only when the program actually references the
 * function (the interpreter lazy-loads it on first resolution; the compile path prepends
 * it).
 *
 * <p>
 * Current members:
 * <ul>
 * <li>{@code equalp} -- like {@code equal} but strings/characters compare case
 * insensitively and numbers by value; lite (arrays/hash-tables/structures fall back to
 * {@code eql}).</li>
 * <li>{@code string<} -- case-sensitive lexicographic less-than, returning the mismatch
 * index or nil.</li>
 * </ul>
 */
public final class LispPreludeLibrary {

	// name -> canonical-shape source (bare cl names, like json.lisp: needs no package
	// resolution). Order is preserved so prepended definitions keep a stable order.
	private static final Map<String, String> SOURCES = new LinkedHashMap<>();

	static {
		SOURCES.put(LispNames.EQUALP, """
				(defun equalp (a b)
				  (cond ((and (numberp a) (numberp b)) (= a b))
				        ((and (stringp a) (stringp b)) (string-equal a b))
				        ((and (characterp a) (characterp b))
				         (char= (char-downcase a) (char-downcase b)))
				        ((and (consp a) (consp b))
				         (and (equalp (car a) (car b)) (equalp (cdr a) (cdr b))))
				        (t (eql a b))))
				""");
		SOURCES.put(LispNames.STRING_LT, """
				(defun string< (a b)
				  (let* ((sa (string a)) (sb (string b))
				         (la (length sa)) (lb (length sb)))
				    (labels ((cmp (i)
				               (cond ((and (>= i la) (>= i lb)) nil)
				                     ((>= i la) i)
				                     ((>= i lb) nil)
				                     ((char< (char sa i) (char sb i)) i)
				                     ((char< (char sb i) (char sa i)) nil)
				                     (t (cmp (+ i 1))))))
				      (cmp 0))))
				""");
	}

	private static final Map<String, List<LispVal>> CACHE = new ConcurrentHashMap<>();

	private LispPreludeLibrary() {
	}

	/**
	 * Returns the parsed definition for a prelude function. Parsed once and cached.
	 * @param name a prelude function name (bare)
	 * @return the library forms (a single {@code defun})
	 */
	public static List<LispVal> formsFor(String name) {
		return CACHE.computeIfAbsent(member(name), n -> {
			String source = SOURCES.get(n);
			if (source == null) {
				throw new IllegalArgumentException(n + " is not a prelude function");
			}
			return LispReader.readAllFromString(source);
		});
	}

	/**
	 * Returns whether {@code name} designates a prelude function (bare or
	 * package-qualified).
	 * @param name a resolved function name
	 * @return true if it is a prelude function
	 */
	public static boolean isPreludeFunction(String name) {
		return SOURCES.containsKey(member(name));
	}

	/**
	 * The compile-path pre-pass: for each prelude function the program references but
	 * does not define itself, prepends its {@code defun}. A program that uses none is
	 * returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the referenced prelude definitions spliced in
	 */
	public static List<LispVal> process(List<LispVal> program) {
		List<String> referenced = new ArrayList<>();
		for (String name : SOURCES.keySet()) {
			if (referencesName(program, name) && !definesName(program, name)) {
				referenced.add(name);
			}
		}
		if (referenced.isEmpty()) {
			return program;
		}
		List<LispVal> out = new ArrayList<>();
		for (String name : referenced) {
			out.addAll(formsFor(name));
		}
		out.addAll(program);
		return out;
	}

	private static boolean definesName(List<LispVal> program, String name) {
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& LispNames.DEFUN.equals(member(op.name())) && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispSymbol defName && name.equals(member(defName.name()))) {
				return true;
			}
		}
		return false;
	}

	private static boolean referencesName(List<LispVal> program, String name) {
		for (LispVal form : program) {
			if (referencesName(form, name)) {
				return true;
			}
		}
		return false;
	}

	private static boolean referencesName(LispVal form, String name) {
		return switch (form) {
			case LispSymbol sym -> name.equals(member(sym.name()));
			case LispCons cons -> referencesName(cons.car(), name) || referencesName(cons.cdr(), name);
			default -> false;
		};
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

}
