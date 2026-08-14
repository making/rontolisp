package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispPackageException;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.PackageResolver;
import am.ik.rontolisp.UiopExports;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;

/**
 * The one home of every {@code uiop} definition: the Lisp-source implementations
 * ({@code uiop-<sub-package>.lisp} on the classpath, one per sub-package that has any)
 * plus a {@code not-implemented-error} STUB, synthesized from {@link UiopExports the
 * inventory}, for every export nothing implements yet.
 *
 * <p>
 * The point of the stubs is that no uiop name may reach a caller as
 * {@code The function UIOP:X is undefined}: uiop is 429 external symbols and this
 * implementation fills them in over many changes, so "we cannot do that here" needs a
 * name from the start. That name is upstream's own {@code uiop:not-implemented-error},
 * and the stub reports the operation it stands for. Filling a name in means adding its
 * real definition to the matching {@code .lisp} resource -- the stub for it then
 * disappears on its own, because a name the resources define is never stubbed.
 *
 * <p>
 * Every definition is keyed by its HOME-package spelling ({@code UIOP/OS:GETENV}, not
 * {@code UIOP:GETENV}): {@code uiop} imports each member from the sub-package that
 * defines it ({@code PackageRegistry}), so that is what {@code PackageResolver} rewrites
 * a {@code uiop:} occurrence into and therefore what a definition of it must carry.
 *
 * <p>
 * Three groups of names are deliberately NOT stubbed, because something else already
 * defines them:
 * <ul>
 * <li>{@link #JAVA_DEFINED} -- built-ins the interpreter defines in Java and the
 * compilers lower directly ({@code getenv}, {@code symbol-call},
 * {@code add-package-local-nickname});</li>
 * <li>{@code LispMacroExpander.hasUiopMacroExpansion} -- forms with a real expansion
 * ({@code if-let}, {@code with-temporary-file}, {@code with-deprecation},
 * {@code define-package});</li>
 * <li>the folds {@code LispMacroExpander.expandUiopStubCall} performs
 * ({@code file-exists-p}, {@code native-namestring}) -- those DO carry a Lisp definition
 * here as well, so {@code #'uiop:file-exists-p} is a value like any other.</li>
 * </ul>
 *
 * <p>
 * Consumers mirror {@code LispPreludeLibrary}: the interpreter lazy-loads ONE name's
 * forms on first resolution, and the compile path prepends only the definitions the
 * program reaches, computed to a fixpoint. Splicing all 429 and letting
 * {@code LibraryDefunPruner} shake them out again would cost every uiop-using program a
 * full resolution pass over 429 definitions; selecting up front is the same saving one
 * step earlier, and it is why uiop is not in the pruner's prunable set (the usocket
 * precedent -- {@code .kb/uiop.md}).
 */
public final class UiopLibrary {

	/**
	 * Sub-package to the classpath resource carrying its real definitions. A sub-package
	 * absent here has no Lisp-source implementation yet, so every one of its exports is
	 * stubbed.
	 */
	private static final Map<String, String> RESOURCES = Map.of("UIOP/PACKAGE", "uiop-package.lisp", "UIOP/UTILITY",
			"uiop-utility.lisp", "UIOP/PATHNAME", "uiop-pathname.lisp", "UIOP/FILESYSTEM", "uiop-filesystem.lisp",
			"UIOP/STREAM", "uiop-stream.lisp", "UIOP/IMAGE", "uiop-image.lisp");

	/**
	 * Members the interpreter defines in Java (and the compilers lower or wrap
	 * themselves), so a stub would shadow a working built-in. {@code getenv} is the only
	 * spelling of "read an environment variable" rontolisp offers (Common Lisp has none)
	 * and is a per-backend primitive; {@code symbol-call} is late binding through the
	 * runtime intern; {@code add-package-local-nickname} reaches the package registry.
	 */
	private static final Set<String> JAVA_DEFINED = Set.of(LispNames.GETENV, LispNames.SYMBOL_CALL,
			LispNames.ADD_PACKAGE_LOCAL_NICKNAME);

	/** The {@code &rest} parameter of a synthesized stub; its arguments are ignored. */
	private static final String STUB_ARGS = "%UIOP-STUB-ARGS";

	/**
	 * The built tables: every uiop member's definitions, keyed by its home-qualified name
	 * and in inventory order, plus the subset that is REALLY implemented (a Lisp
	 * definition here, a Java built-in or a built-in macro expansion) rather than
	 * standing behind a synthesized {@code not-implemented-error} stub.
	 * {@code UiopCoverageTest} measures the second against the first.
	 *
	 * @param definitions the definition forms per home-qualified name
	 * @param implemented the home-qualified names with a real implementation
	 */
	record Tables(Map<String, List<LispVal>> definitions, Set<String> implemented) {
	}

	@org.jspecify.annotations.Nullable
	private static volatile Tables tables;

	private UiopLibrary() {
	}

	/**
	 * Returns the definitions of one uiop member, keyed by its HOME-package spelling.
	 * Usually one form; two when upstream defines the name twice
	 * ({@code not-implemented-error} is a condition AND the function that signals it).
	 * @param qualifiedName the home-qualified name ({@code UIOP/OS:GETENV})
	 * @return the forms, or an empty list when nothing defines the name
	 */
	public static List<LispVal> formsFor(String qualifiedName) {
		return tables().definitions().getOrDefault(qualifiedName, List.of());
	}

	/**
	 * Returns whether the library carries a definition (real or stub) for the name.
	 * @param qualifiedName the home-qualified name
	 * @return {@code true} when {@link #formsFor} is non-empty
	 */
	public static boolean definesName(String qualifiedName) {
		return tables().definitions().containsKey(qualifiedName);
	}

	/**
	 * Returns whether the name has a REAL implementation rather than a synthesized
	 * {@code not-implemented-error} stub: a definition in one of the {@code uiop-*.lisp}
	 * resources, a Java built-in, or a built-in macro expansion. This is what
	 * {@code UiopCoverageTest} counts.
	 * @param qualifiedName the home-qualified name
	 * @return {@code true} when the name is implemented
	 */
	public static boolean isImplemented(String qualifiedName) {
		return tables().implemented().contains(qualifiedName);
	}

	/**
	 * Returns every name the library defines, in inventory order.
	 * @return the home-qualified names
	 */
	public static Set<String> definedNames() {
		return tables().definitions().keySet();
	}

	/**
	 * Returns every CONDITION- or CLASS-kind member the library defines, in inventory
	 * order. The interpreter registers all of them on its first uiop load, which is the
	 * {@code not-implemented-error} rule the skeleton established generalized to the
	 * whole inventory -- and for the same reason, one step further: a handler's type test
	 * is built from the class tags known when the {@code handler-bind} form is EXPANDED,
	 * so a condition class that first appears while the body runs is invisible to the
	 * handler that was meant to catch it. The compile paths splice every reachable
	 * definition before anything runs and never see that window; registering the 19
	 * classes up front is what closes it here. They are
	 * {@code define-condition}/{@code defclass} forms only, so the cost is a registry
	 * entry each.
	 * @return the condition and class names, home-qualified
	 */
	public static Set<String> conditionAndClassNames() {
		Set<String> names = new LinkedHashSet<>();
		for (UiopExports.Entry entry : UiopExports.entries()) {
			if (entry.is("condition") || entry.is("class")) {
				String qualified = UiopExports.qualified(entry.symbol());
				if (definesName(qualified)) {
					names.add(qualified);
				}
			}
		}
		return names;
	}

	/**
	 * Returns the name plus every library definition reachable from it, to a fixpoint and
	 * in inventory order: the interpreter's lazy load needs the same closure
	 * {@link #process} splices on the compile paths, or the two disagree about what a
	 * definition brings with it.
	 *
	 * <p>
	 * The case that forces it is a name a body mentions but never CALLS:
	 * {@code style-warn} signals {@code (make-condition 'uiop:simple-style-warning ...)},
	 * and a quoted condition name is not a function resolution, so nothing would ever
	 * trigger the class's own load -- the warning then had no report and no
	 * {@code style-warning} supertype on the interpreter while the compile paths had
	 * both.
	 * @param qualifiedName the home-qualified name
	 * @return the closure, in inventory order (empty when nothing defines the name)
	 */
	public static Set<String> closureOf(String qualifiedName) {
		if (!definesName(qualifiedName)) {
			return Set.of();
		}
		Set<String> selected = new LinkedHashSet<>();
		selected.add(qualifiedName);
		boolean grew = true;
		while (grew) {
			grew = false;
			for (String name : List.copyOf(selected)) {
				Set<String> referenced = new LinkedHashSet<>();
				for (LispVal form : formsFor(name)) {
					collectSymbols(form, referenced);
				}
				for (String candidate : definedNames()) {
					if (referenced.contains(candidate) && selected.add(candidate)) {
						grew = true;
					}
				}
			}
		}
		// Inventory order, like the splice: the load order of a group is then the same
		// on every backend.
		Set<String> ordered = new LinkedHashSet<>();
		for (String name : definedNames()) {
			if (selected.contains(name)) {
				ordered.add(name);
			}
		}
		return ordered;
	}

	/**
	 * Returns every definition, in inventory order: the whole library. Used by the
	 * coverage test and by anything that wants uiop whole rather than selected.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> out = new ArrayList<>();
		tables().definitions().values().forEach(out::addAll);
		return List.copyOf(out);
	}

	/**
	 * Returns whether the given symbol name is qualified with {@code uiop} or one of its
	 * sub-packages.
	 * @param symbolName the symbol name as written
	 * @return {@code true} when the name belongs to the uiop family
	 */
	public static boolean isUiopQualified(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && UiopExports.isUiopFamily(qn.pkg());
	}

	/**
	 * The compile-path pre-pass: prepends the definitions of every uiop member the
	 * program reaches, and nothing else. Selection runs on a
	 * {@link PackageResolver#resolveProgram(List) resolved} copy so a {@code uiop:name}
	 * occurrence is matched as the home-package symbol it denotes, and to a fixpoint
	 * because a uiop definition may call another one.
	 *
	 * <p>
	 * It must run BEFORE the prelude selection: the bodies here call {@code namestring} /
	 * {@code pathname} / {@code merge-pathnames} / {@code directory} and the prelude's
	 * own helpers, and the prelude selects on what the program contains at the time it
	 * runs. That is why {@code LispPreludeLibrary.process} CALLS this rather than sitting
	 * beside it in the pipeline: the two are mutually dependent (the prelude's
	 * {@code %temp-file-name} calls uiop back), so they are one pass with a fixed order,
	 * and a pipeline that ran only the prelude would reintroduce exactly the
	 * "{@code The function UIOP:X is undefined}" this library exists to abolish.
	 * Re-running it is a no-op: a definition the program already carries is not spliced
	 * again.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the referenced uiop definitions spliced in
	 */
	public static List<LispVal> process(List<LispVal> program) {
		List<LispVal> resolved;
		try {
			resolved = new PackageResolver().resolveProgram(program);
		}
		catch (LispPackageException ex) {
			// Not this pass's error to report -- the backends run the identical
			// resolution first thing. Without canonical spellings a uiop reference
			// cannot be recognised at all, so select nothing.
			return program;
		}
		Set<String> occurring = new LinkedHashSet<>();
		Set<String> alreadyDefined = new LinkedHashSet<>();
		for (LispVal form : resolved) {
			collectSymbols(form, occurring);
			String defined = definedName(form);
			if (defined != null) {
				alreadyDefined.add(defined);
			}
		}
		Set<String> selected = new LinkedHashSet<>();
		for (String name : definedNames()) {
			if (alreadyDefined.contains(name)) {
				// The dedup guard every library splice carries: the program already
				// carries this definition (a previous run of this pass, an
				// asdf:load-system splice, or the user's own), so a second copy would
				// only redefine it.
				continue;
			}
			if (occurring.contains(name) || reachedBySurfaceForm(name, occurring)) {
				selected.add(name);
			}
		}
		boolean grew = true;
		while (grew) {
			grew = false;
			for (String name : List.copyOf(selected)) {
				Set<String> referenced = new LinkedHashSet<>();
				for (LispVal form : formsFor(name)) {
					collectSymbols(form, referenced);
				}
				for (String candidate : definedNames()) {
					if (referenced.contains(candidate) && !alreadyDefined.contains(candidate)
							&& selected.add(candidate)) {
						grew = true;
					}
				}
			}
		}
		if (selected.isEmpty()) {
			return program;
		}
		List<LispVal> out = new ArrayList<>();
		// Emit in inventory order, not discovery order, so the spliced prefix is stable.
		for (Map.Entry<String, List<LispVal>> entry : tables().definitions().entrySet()) {
			if (selected.contains(entry.getKey())) {
				out.addAll(entry.getValue());
			}
		}
		out.addAll(program);
		return out;
	}

	/**
	 * The definitions a uiop MACRO's expansion calls, per macro. Every one of these
	 * expansions runs inside the expression compilers, long after this pass, so the names
	 * it introduces never occur in the program this pass looks at -- without the table
	 * the compiled program says {@code The function UIOP/UTILITY:X is undefined} at run
	 * time while the interpreter (which lazy-loads on resolution) works.
	 *
	 * <p>
	 * Only the DIRECT callee needs listing: {@link #process}'s fixpoint pulls in whatever
	 * that one reaches in turn ({@code call-with-muffled-conditions} drags
	 * {@code match-any-condition-p}, {@code match-condition-p} and {@code find-symbol*}
	 * along). {@code with-temporary-file} reaches its three through the prelude's
	 * {@code %temp-file-name}, and the delete is pulled in whatever {@code :keep} says,
	 * because reading that option here would duplicate the expansion's rule
	 * ({@code LispPreludeLibrary.referencedBySurfaceForm} makes the mirror-image decision
	 * for the prelude half).
	 */
	private static final Map<String, List<String>> MACRO_EXPANSION_CALLEES = Map.of(LispNames.WITH_TEMPORARY_FILE,
			List.of(LispNames.ENSURE_DIRECTORY_PATHNAME, LispNames.DEFAULT_TEMPORARY_DIRECTORY,
					LispNames.DELETE_FILE_IF_EXISTS),
			LispNames.WITH_MUFFLED_CONDITIONS, List.of(LispNames.CALL_WITH_MUFFLED_CONDITIONS), LispNames.UIOP_DEBUG,
			List.of(LispNames.LOAD_UIOP_DEBUG_UTILITY), LispNames.LATEST_TIMESTAMP_F,
			List.of(LispNames.LATEST_TIMESTAMP));

	/** Whether a definition the program never NAMES is nonetheless reached from it. */
	private static boolean reachedBySurfaceForm(String name, Set<String> occurring) {
		for (Map.Entry<String, List<String>> entry : MACRO_EXPANSION_CALLEES.entrySet()) {
			if (!occurring.contains(UiopExports.qualified(entry.getKey()))) {
				continue;
			}
			for (String callee : entry.getValue()) {
				if (name.equals(UiopExports.qualified(callee))) {
					return true;
				}
			}
		}
		return false;
	}

	private static void collectSymbols(LispVal form, Set<String> into) {
		switch (form) {
			case LispSymbol sym -> into.add(sym.name());
			case LispCons cons -> {
				collectSymbols(cons.car(), into);
				collectSymbols(cons.cdr(), into);
			}
			default -> {
			}
		}
	}

	private static Tables tables() {
		Tables cached = tables;
		if (cached == null) {
			synchronized (UiopLibrary.class) {
				cached = tables;
				if (cached == null) {
					cached = build();
					tables = cached;
				}
			}
		}
		return cached;
	}

	private static Tables build() {
		Map<String, List<LispVal>> real = new LinkedHashMap<>();
		for (Map.Entry<String, String> resource : RESOURCES.entrySet()) {
			for (LispVal form : LispReader.readAllFromString(readSource(resource.getValue()), Features.INTERPRETER)) {
				String defined = definedName(form);
				if (defined == null) {
					throw new IllegalStateException(resource.getValue() + ": not a definition form: " + form.print());
				}
				real.computeIfAbsent(defined, ignored -> new ArrayList<>()).add(form);
			}
		}
		Map<String, List<LispVal>> out = new LinkedHashMap<>();
		Set<String> implemented = new LinkedHashSet<>();
		for (UiopExports.Entry entry : UiopExports.entries()) {
			String qualified = UiopExports.qualified(entry.symbol());
			if (implemented.contains(qualified) || out.containsKey(qualified)) {
				// A second sub-package re-exporting the same symbol: one definition.
				continue;
			}
			List<LispVal> defined = real.get(qualified);
			if (defined != null) {
				out.put(qualified, List.copyOf(defined));
				implemented.add(qualified);
			}
			else if (JAVA_DEFINED.contains(entry.symbol()) || LispMacroExpander.hasUiopMacroExpansion(entry.symbol())) {
				implemented.add(qualified);
			}
			else {
				out.put(qualified, stubFor(qualified, entry));
			}
		}
		Set<String> unknown = new LinkedHashSet<>(real.keySet());
		unknown.removeAll(out.keySet());
		if (!unknown.isEmpty()) {
			throw new IllegalStateException("uiop-*.lisp defines names uiop does not export: " + unknown);
		}
		return new Tables(Map.copyOf(out), Set.copyOf(implemented));
	}

	/**
	 * The definitions standing in for a member nothing implements yet, in the shape
	 * upstream gives the name so the stub answers the same predicate the real definition
	 * will ({@code fboundp} for a function, {@code boundp} for a variable, a defined type
	 * for a condition). A {@code constant} gets a {@code defvar} rather than a
	 * {@code defconstant}: the value is a placeholder, and pinning a placeholder as a
	 * constant only makes the real definition a redefinition.
	 */
	private static List<LispVal> stubFor(String qualified, UiopExports.Entry entry) {
		List<LispVal> forms = new ArrayList<>();
		LispSymbol name = new LispSymbol(qualified);
		if (entry.is("condition")) {
			forms.add(list(new LispSymbol(LispNames.DEFINE_CONDITION), name, list(new LispSymbol(LispNames.ERROR)),
					LispNil.INSTANCE));
		}
		if (entry.is("class")) {
			forms.add(list(new LispSymbol(LispNames.DEFCLASS), name, LispNil.INSTANCE, LispNil.INSTANCE));
		}
		if (entry.is("type")) {
			forms.add(list(new LispSymbol(LispNames.DEFTYPE), name, LispNil.INSTANCE, LispTrue.INSTANCE));
		}
		if (entry.is("function") || entry.is("macro")) {
			// A macro-kind stub is a defun too, so the name is fboundp and usable as a
			// value; the CALL form is lowered without evaluating its arguments by
			// LispMacroExpander.expandUiopStubCall, which is what a macro that does
			// nothing must do with the forms it was handed.
			forms.add(list(new LispSymbol(LispNames.DEFUN), name,
					list(new LispSymbol(LispNames.LAMBDA_REST), new LispSymbol(STUB_ARGS)),
					list(new LispSymbol(UiopExports.qualified(LispNames.NOT_IMPLEMENTED_ERROR)),
							new LispString(qualified))));
		}
		if (entry.is("variable") || entry.is("constant")) {
			forms.add(list(new LispSymbol(LispNames.DEFVAR), name, LispNil.INSTANCE));
		}
		if (forms.isEmpty()) {
			throw new IllegalStateException("no stub shape for kind " + entry.kind() + " (" + qualified + ")");
		}
		return List.copyOf(forms);
	}

	private static LispVal list(LispVal... items) {
		LispVal result = LispNil.INSTANCE;
		for (int i = items.length - 1; i >= 0; i--) {
			result = new LispCons(items[i], result);
		}
		return result;
	}

	private static @org.jspecify.annotations.Nullable String definedName(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op && cons.cdr() instanceof LispCons rest
				&& rest.car() instanceof LispSymbol defined && DEFINITION_OPERATORS.contains(op.name())) {
			return defined.name();
		}
		return null;
	}

	private static final Set<String> DEFINITION_OPERATORS = Set.of(LispNames.DEFUN, LispNames.DEFMACRO,
			LispNames.DEFVAR, LispNames.DEFPARAMETER, LispNames.DEFCONSTANT, LispNames.DEFINE_CONDITION,
			LispNames.DEFCLASS, LispNames.DEFTYPE, LispNames.DEFGENERIC);

	private static String readSource(String resource) {
		try (InputStream in = UiopLibrary.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException(resource + " is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
