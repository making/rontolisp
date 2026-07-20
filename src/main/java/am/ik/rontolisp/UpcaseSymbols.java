package am.ik.rontolisp;

import java.util.Locale;
import java.util.Set;

/**
 * The canonical fold of the reader's upcase premise. Always on for user source; the lexer
 * upcases every unescaped symbol character, mirroring Common Lisp's {@code :upcase}
 * readtable case; this fold then maps the names whose canonical rontolisp spelling is
 * lowercase back down, so the token stream is identical to reading lowercase source and
 * nothing downstream (resolver, splice scanners, evaluator, all compiler backends) needs
 * mode awareness. Only genuinely user-level symbols stay upcased -- which is the point:
 * {@code name} and {@code NAME} in source both read as {@code NAME}, giving
 * case-folding-reliant Common Lisp code (data keywords written in either case,
 * {@code (intern (string-upcase ...))} macro name synthesis) its expected behavior.
 *
 * <p>
 * What folds to lowercase:
 * <ul>
 * <li>bare names owned by the {@code cl} package (functions, macros, special forms,
 * variables, type specifiers, car/cdr compositions) plus the read-time constants and the
 * seeded condition-type names below;</li>
 * <li>lambda-list markers (any {@code &}-initial name);</li>
 * <li>package-qualified names whose package part is a built-in package or nickname
 * ({@code RL:FETCH} to {@code rl:fetch}) -- the member part folds too, since every
 * built-in package's symbols are lowercase-authored, except for
 * {@code cl-user}/{@code common-lisp-user}, whose members are the user's own upcased
 * symbols;</li>
 * <li>keyword or {@code #:} designators of built-in packages ({@code (:use #:CL)},
 * {@code (in-package :CL-USER)}, {@code (intern "X" :KEYWORD)}).</li>
 * </ul>
 * Everything else -- user symbols, data keywords like {@code :ELEMENTS}, user package
 * prefixes -- keeps its upcased spelling, self-consistently. Escaped
 * ({@code |...|}/{@code \x}) characters were never upcased by the lexer; the fold still
 * applies to the finished name, so {@code |CAR|} is {@code car} like in CL (the one
 * deviation: {@code |car|} is also {@code car}, because rontolisp's canonical spelling of
 * the standard symbols is lowercase).
 */
public final class UpcaseSymbols {

	/**
	 * Canonical lowercase names that are not in {@code PackageRegistry.CL_SYMBOLS}: the
	 * reader-level constants recognized by exact lowercase match in {@code LispReader},
	 * and the seeded condition-type names ({@code ClosRegistry}) that
	 * {@code handler-case}/{@code typep} match by name.
	 */
	private static final Set<String> EXTRA_CANONICAL = Set.of("t", "nil", "pi", "most-positive-fixnum",
			"most-negative-fixnum",
			// Standard type-specifier names that are not also function/macro names in
			// PackageRegistry (whose CL_TYPES covers only the numeric/sequence ones):
			// typecase/typep in real libraries dispatch on these.
			"symbol", "hash-table", "keyword", "package", "pathname", "readtable", "random-state", "bit-vector",
			"simple-bit-vector", "compiled-function", "generic-function", "method", "class", "standard-class",
			"standard-object", "structure-object", "restart",
			// CLOS local operators / standard generics matched by name in the expander.
			"call-next-method", "next-method-p", "initialize-instance", "print-object",
			// Declaration specifiers matched by name ((declare (special x)) and
			// friends); declarations are otherwise no-ops, but special MUST match.
			"special", "ignore", "ignorable", "inline", "notinline", "optimize", "dynamic-extent", "speed", "safety",
			"debug", "space", "compilation-speed", "ftype",
			// The seeded condition hierarchy (keep in sync with ClosRegistry's
			// constructor).
			"condition", "serious-condition", "simple-condition", "simple-error", "warning", "simple-warning",
			"style-warning", "parse-error", "type-error", "stream-error", "end-of-file", "file-error",
			"arithmetic-error", "division-by-zero", "control-error", "program-error", "package-error", "cell-error",
			"unbound-variable", "undefined-function");

	private UpcaseSymbols() {
	}

	/**
	 * Folds a fully-upcased unescaped symbol name to its canonical spelling. Called by
	 * the lexer for every non-{@code INTERNAL} read, and by the runtime
	 * {@code intern}/{@code find-symbol}.
	 * @param name the symbol name as lexed (unescaped characters upcased)
	 * @return the canonical name
	 */
	public static String canonicalize(String name) {
		if (name.startsWith("#:")) {
			return "#:" + foldPackageDesignator(name.substring(2));
		}
		if (name.startsWith(":")) {
			return ":" + foldPackageDesignator(name.substring(1));
		}
		int colon = name.indexOf(':');
		if (colon > 0) {
			String pkg = name.substring(0, colon).toLowerCase(Locale.ROOT);
			if (!PackageRegistry.isBuiltinPackageName(pkg)) {
				// A %-prefixed member is an internal lowercase-authored helper even in
				// a user/wit package (kv::%open): fold the member, keep the package
				// part's spelling rules.
				int memberStart = name.charAt(colon + 1) == ':' ? colon + 2 : colon + 1;
				if (memberStart < name.length() && name.charAt(memberStart) == '%') {
					return name.substring(0, memberStart) + name.substring(memberStart).toLowerCase(Locale.ROOT);
				}
				return name;
			}
			// cl-user members are the user's own symbols, whose canonical spelling is
			// the upcased one; every other built-in package is lowercase-authored.
			if (LispNames.CL_USER_PKG.equals(PackageRegistry.canonicalBuiltinName(pkg))) {
				return pkg + name.substring(colon);
			}
			return pkg + name.substring(colon).toLowerCase(Locale.ROOT);
		}
		if (name.startsWith("&")) {
			return name.toLowerCase(Locale.ROOT);
		}
		// %-prefixed names are rontolisp-internal helpers by convention, authored (and
		// matched Java-side) in lowercase; fold them so a source spelling like %ASET
		// reaches the internal %aset. A folded library's own %-helpers fold on both the
		// defun and its call sites, so they stay self-consistent too.
		if (name.startsWith("%")) {
			return name.toLowerCase(Locale.ROOT);
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (PackageRegistry.isClSymbol(lower) || EXTRA_CANONICAL.contains(lower)) {
			return lower;
		}
		// A bare built-in package name is a package designator ((in-package rontolisp),
		// (rontolisp:list-functions 'cl)); the packages are lowercase-canonical.
		if (PackageRegistry.isBuiltinPackageName(lower)) {
			return lower;
		}
		return name;
	}

	// A keyword or #: designator body: folds only when it names a built-in package
	// (or nickname), so (in-package :CL-USER) and (:use #:CL) resolve while data
	// keywords like :ELEMENTS stay upcased.
	private static String foldPackageDesignator(String body) {
		String lower = body.toLowerCase(Locale.ROOT);
		return PackageRegistry.isBuiltinPackageName(lower) ? lower : body;
	}

	/**
	 * The complete, enumerated set of lowercase bare names that {@link #canonicalize}
	 * folds a bare (unqualified, unprefixed) upcased token to -- exactly the names for
	 * which the bare-name branch returns the lowercase spelling: the {@code cl} symbols,
	 * every car/cdr composition (length 4-6; {@code car}/{@code cdr} are already
	 * {@code cl} symbols), the read-time constants and seeded condition types in
	 * {@link #EXTRA_CANONICAL}, and the built-in package/nickname names. The compiled
	 * backends' embedded reader runtimes bake this set (they cannot call
	 * {@link #canonicalize}), so it is the single source of truth for their fold; keep it
	 * in step with {@link #canonicalize}'s bare-name branch.
	 * @return the foldable bare names, lowercase
	 */
	public static Set<String> foldableBareNames() {
		Set<String> names = new java.util.HashSet<>(PackageRegistry.clSymbols());
		names.addAll(EXTRA_CANONICAL);
		names.addAll(PackageRegistry.builtinPackageAndNicknameNames());
		// car/cdr compositions are a pattern in canonicalize (via isCarCdrComposition);
		// enumerate the finite length-4..6 members here so the baked set is complete.
		for (int len = 4; len <= 6; len++) {
			enumerateCarCdr(new char[len], 1, len - 1, names);
		}
		return names;
	}

	private static void enumerateCarCdr(char[] buf, int index, int endExclusive, Set<String> out) {
		if (index == endExclusive) {
			buf[0] = 'c';
			buf[buf.length - 1] = 'r';
			out.add(new String(buf));
			return;
		}
		buf[index] = 'a';
		enumerateCarCdr(buf, index + 1, endExclusive, out);
		buf[index] = 'd';
		enumerateCarCdr(buf, index + 1, endExclusive, out);
	}

	/**
	 * The (lowercase) built-in package and nickname names -- exactly what
	 * {@link PackageRegistry#isBuiltinPackageName} accepts, which the qualified-name and
	 * {@code :}/{@code #:} designator branches of {@link #canonicalize} consult. Baked by
	 * the compiled backends' reader runtimes alongside {@link #foldableBareNames}.
	 * @return the built-in package designator names, lowercase
	 */
	public static Set<String> foldablePackageNames() {
		return PackageRegistry.builtinPackageAndNicknameNames();
	}

	/**
	 * The foldable bare names as a single {@code \n}-delimited blob, sorted
	 * (deterministic across builds so the emitted class/module stays byte-identical) with
	 * a leading and trailing {@code \n}. The compiled reader runtimes bake this and test
	 * membership by searching for {@code \n} + name + {@code \n} -- a newline can never
	 * appear in a symbol token, which the whitespace-terminated reader guarantees. Both
	 * the JVM and WASM backends bake this exact string, so their read-time fold is
	 * byte-identical.
	 * @return the delimited fold-name blob
	 */
	public static String foldNamesBlob() {
		return delimitedBlob(foldableBareNames());
	}

	/**
	 * The foldable built-in package/nickname names as a {@code \n}-delimited blob, in the
	 * same shape as {@link #foldNamesBlob}.
	 * @return the delimited package-name blob
	 */
	public static String foldPackageNamesBlob() {
		return delimitedBlob(foldablePackageNames());
	}

	private static String delimitedBlob(Set<String> names) {
		StringBuilder sb = new StringBuilder("\n");
		for (String name : new java.util.TreeSet<>(names)) {
			sb.append(name).append('\n');
		}
		return sb.toString();
	}

}
