package am.ik.rontolisp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * Resolves package-qualified and unqualified symbols against a {@link PackageRegistry}
 * and enforces the package discipline, as a read/compile-time pass that runs before the
 * evaluator and the compilers. It tracks the current package (driven by
 * {@code in-package} directives) and rewrites each top-level form into a canonical shape:
 *
 * <ul>
 * <li>{@code cl} standard symbols and {@code cl-user} user symbols become bare names (so
 * the existing evaluator/compilers handle them unchanged);</li>
 * <li>symbols of non-default packages become qualified names: {@code pkg:name} for an
 * external symbol (e.g. {@code rontolisp:version}), {@code pkg::name} for an internal one
 * (e.g. {@code rontolisp::%json-parse}), so the canonical form re-resolves to
 * itself;</li>
 * <li>{@code *package*} stays the bare {@code cl} variable it is: its value is READ AT
 * RUN TIME on every backend, as Common Lisp's dynamic {@code *package*} is (the runtime
 * value is the package keyword {@code find-package} answers);</li>
 * <li>{@code (in-package P)} is consumed and replaced by the runtime assignment
 * {@code (setq *package* :P)}, which keeps the run-time value in step with the
 * resolution-time state this pass tracks;</li>
 * <li>{@code (defpackage NAME (:use ...) (:export ...))} registers a new package and is
 * likewise consumed and replaced by a quoted package symbol.</li>
 * </ul>
 *
 * Mirroring Common Lisp, a single-colon qualifier only reaches external (exported)
 * symbols; internal symbols require the double colon. The other hard error is an
 * unqualified {@code cl} symbol used in a package that does not use {@code cl} (such as
 * {@code rontolisp}). The instance keeps the current-package state across calls, so a
 * REPL session keeps {@code in-package} in effect across inputs.
 */
public final class PackageResolver {

	private final PackageRegistry registry;

	private String currentPackage = LispNames.CL_USER_PKG;

	/**
	 * The stack of packages saved by a {@code %push-package} marker (and around a runtime
	 * {@code load}), restored by the matching {@code %pop-package}. Keeps a loaded file's
	 * internal {@code in-package} from leaking past the load, mirroring Common Lisp
	 * binding {@code *package*} for the duration of {@code load} (see
	 * {@link #pushPackage}).
	 */
	private final Deque<String> packageStack = new ArrayDeque<>();

	/**
	 * Whether resolution is inside a {@code defmacro}/{@code macrolet} definition, where
	 * quoted data comes from backquote templates and its symbols are resolved against the
	 * defining package (see {@link #resolveCons}).
	 */
	private boolean inMacroDefinition = false;

	/**
	 * Whether resolution is inside the options of a {@code wasm-export}/
	 * {@code wasm-import} directive, whose quoted values are HOST-facing data (an export
	 * field name, a WIT type) rather than package-scoped symbols -- so the
	 * quoted-lone-symbol resolution in {@link #resolveCons} must not touch them.
	 */
	private boolean inHostFacingData = false;

	/**
	 * Whether resolution is inside a quoted datum. Data position is more permissive than
	 * code position: Common Lisp's reader interns whatever it reads in the current
	 * package, so {@code '(car x)} under a package that does not use {@code cl} is the
	 * pair of that package's own symbols -- not the error the same names would be in
	 * operator position -- and a quoted {@code *package*} is the SYMBOL, not the current
	 * package's name -- and so, since {@code *package*} is a runtime variable read, is an
	 * evaluated one (see {@link #resolveUnqualified}).
	 */
	private boolean inQuotedData = false;

	/**
	 * Whether resolution is on behalf of the runtime {@code intern}, whose name is
	 * VERBATIM: the reader's case-fold retries in {@link #resolveQualified} and
	 * {@link #resolveUnqualified} (an upcased source spelling reaching a lower-kebab
	 * wit-import member) must not apply, or an interned {@code "Abc"} would fold onto a
	 * recorded {@code abc} -- the case-sensitive Scheme symbols live in one package.
	 */
	private boolean exactCase = false;

	/**
	 * The external set each package was DECLARED with -- its {@code defpackage}
	 * {@code :export} clause, or the set the registry seeded a built-in package with --
	 * captured the first time a runtime {@code export}/{@code unexport} directive touches
	 * that package.
	 * <p>
	 * A symbol IS its canonical spelling here, so the spelling has to be a property of
	 * the SYMBOL and not of a package state that changes underneath it: an
	 * {@code export}/{@code unexport} changes ACCESSIBILITY (which colon a reference may
	 * use, and what a {@code use-package} inherits) and never identity. Without this,
	 * exporting re-keyed the symbol -- a {@code defun} made before the export kept
	 * {@code pkg::name} while every later {@code pkg:name} call site named a symbol
	 * nothing defined. A package the directives never touch has no entry and reads its
	 * live external set, so the {@code defpackage}-only corpus resolves byte-identically.
	 * @see #spellsExternal
	 */
	private final Map<String, Set<String>> declaredExternals = new HashMap<>();

	/**
	 * Creates a resolver with a fresh registry of the built-in packages.
	 */
	public PackageResolver() {
		this(new PackageRegistry());
	}

	/**
	 * Creates a resolver over the given registry.
	 * @param registry the package registry
	 */
	public PackageResolver(PackageRegistry registry) {
		this.registry = registry;
	}

	/**
	 * Returns whether the CURRENT package (as tracked across the forms resolved so far)
	 * declares the given name in its {@code :shadow} clause. Used by
	 * {@code UserMacroExpander} to spell a bare canonical CL name explicitly
	 * {@code cl:}-qualified in emitted expansions, so the compilers' own resolution pass
	 * does not re-capture it as the shadowing package's symbol.
	 * @param name the bare symbol name
	 * @return {@code true} when the current package shadows the name
	 */
	public boolean currentPackageShadows(String name) {
		LispPackage current = this.registry.get(this.currentPackage);
		return current != null && current.shadows(name);
	}

	/**
	 * Resolves every form of a program in order, keeping {@code in-package} state across
	 * forms.
	 * @param program the top-level forms
	 * @return the resolved forms
	 */
	public List<LispVal> resolveProgram(List<LispVal> program) {
		this.runtimePackagesMutable = programUsesRuntimePackageMutation(program);
		this.inProgramResolution = true;
		try {
			List<LispVal> out = new ArrayList<>(program.size());
			for (LispVal form : program) {
				out.add(resolve(form));
			}
			return out;
		}
		finally {
			this.inProgramResolution = false;
		}
	}

	/**
	 * Whether the program can create, delete or rename packages at run time -- a call or
	 * {@code #'name} reference to {@code make-package}, {@code delete-package} or
	 * {@code rename-package} outside quoted data. While set, a literal
	 * {@code (find-package X)} over an unknown package stays a call: the package may come
	 * into being later. The backends read this after {@link #resolveProgram} for the same
	 * gate over their lowerings.
	 * @return {@code true} when the last resolved program can mutate packages
	 */
	public boolean runtimePackagesMutable() {
		return this.runtimePackagesMutable;
	}

	private boolean runtimePackagesMutable;

	private boolean inProgramResolution;

	// A reference to a runtime package-mutating operator -- a call head or a #'name
	// value, anywhere outside quoted data (a defun of the same name counts: it is
	// still a program that spells the operator, and staying dynamic there is only
	// less folding, never a wrong answer).
	private static boolean programUsesRuntimePackageMutation(List<LispVal> program) {
		for (LispVal form : program) {
			if (referencesRuntimePackageMutation(form, false)) {
				return true;
			}
		}
		return false;
	}

	private static boolean referencesRuntimePackageMutation(LispVal form, boolean quoted) {
		// The cdr spine is walked in a loop: a frame per element would make the stack
		// ceiling the program's longest list.
		LispVal node = form;
		boolean inQuote = quoted;
		while (true) {
			switch (node) {
				case LispSymbol sym -> {
					return !inQuote && (LispNames.MAKE_PACKAGE.equals(operatorMember(sym))
							|| LispNames.DELETE_PACKAGE.equals(operatorMember(sym))
							|| LispNames.RENAME_PACKAGE.equals(operatorMember(sym)));
				}
				case LispCons cons -> {
					boolean quoteHead = cons.car() instanceof LispSymbol head
							&& LispNames.QUOTE.equals(operatorMember(head));
					if (referencesRuntimePackageMutation(cons.car(), inQuote)) {
						return true;
					}
					node = cons.cdr();
					inQuote = inQuote || quoteHead;
				}
				default -> {
					return false;
				}
			}
		}
	}

	/**
	 * Resolves a single top-level form against the current package state.
	 * @param form the form to resolve
	 * @return the resolved form
	 */
	public LispVal resolve(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op) {
			String member = operatorMember(op);
			if (LispNames.IN_PACKAGE.equals(member)) {
				return resolveInPackage(cons);
			}
			if (LispNames.DEFPACKAGE.equals(member)) {
				return resolveDefpackage(cons);
			}
			// A literal top-level (uiop:define-package ...) / (mgl-pax:define-package
			// ...) is defpackage in the variant's clothing and is consumed exactly like
			// one: dbi's package headers and trivial-utf-8's opening form. The variant's
			// extra tolerance (redefining an existing package) and its extra clauses
			// (:use-reexport, :mix, ...) error loudly in resolveDefpackage until a
			// consumer needs them -- deny by default, like the rest of this subset.
			if (LispNames.DEFINE_PACKAGE.equals(member) && isDefinePackageOperator(op)) {
				return resolveDefpackage(cons, true);
			}
			// mgl-pax:defsection AUTOEXPORTS: each (SYMBOL LOCATIVE) entry of a section
			// body is exported from the current package at load time (mgl-pax's
			// documented default), and trivial-utf-8 has no other export mechanism --
			// its whole public API is exported by its @reference section. Which symbols
			// are external is a read/compile-time notion here, so the entries are
			// consumed into the export set on THIS pass; the form itself still resolves
			// and expands below (the stub macro defines the section variable).
			if ("DEFSECTION".equals(member) && isMglPaxOperator(op)) {
				consumeDefsectionExports(cons);
			}
			// A literal top-level (use-package P) is consumed like in-package: the use
			// list is a read/compile-time notion here, so the directive has to take
			// effect on THIS pass for the forms that follow -- and consuming it is what
			// makes it work on the compiled backends, which have no registry at runtime.
			// A computed designator stays a runtime call (interpreter only).
			if (LispNames.USE_PACKAGE.equals(member)) {
				LispVal consumed = tryConsumeUsePackage(cons);
				if (consumed != null) {
					return consumed;
				}
			}
			// (unuse-package P) is the inverse, consumed for exactly the same reason:
			// the use list is a read/compile-time notion here, so the removal has to
			// take effect on THIS pass for the forms that follow.
			if (LispNames.UNUSE_PACKAGE.equals(member)) {
				LispVal consumed = tryConsumeUnusePackage(cons);
				if (consumed != null) {
					return consumed;
				}
			}
			// A literal top-level (export '(a b)) / (unexport 'a) is consumed for the
			// same
			// reason use-package is: which symbols are external is a read/compile-time
			// notion here, so the directive must take effect on THIS pass for the forms
			// that follow -- and consuming it is what makes it work on the compiled
			// backends, which have no registry at runtime.
			if (LispNames.EXPORT.equals(member) || LispNames.UNEXPORT.equals(member)) {
				LispVal consumed = tryConsumeExport(cons, LispNames.EXPORT.equals(member));
				if (consumed != null) {
					return consumed;
				}
			}
			// A literal top-level (import 'p:sym) is consumed for the same reason: which
			// symbols a package makes accessible unqualified is a read/compile-time
			// notion here, so the directive has to take effect on THIS pass for the
			// forms that follow. It is the runtime spelling of defpackage's
			// :import-from clause and records the same import redirect.
			if (LispNames.IMPORT.equals(member)) {
				LispVal consumed = tryConsumeImport(cons);
				if (consumed != null) {
					return consumed;
				}
			}
			if (LispNames.PUSH_PACKAGE.equals(member)) {
				// The save needs no runtime counterpart: the run-time *package* already
				// holds the package this pass has current, and the restore below
				// re-assigns it explicitly.
				pushPackage();
				return quotedSymbol(this.currentPackage);
			}
			if (LispNames.POP_PACKAGE.equals(member)) {
				popPackage();
				return packageAssignment(this.currentPackage);
			}
			// The ASDF provenance brackets are consumed here too, so a marker never
			// survives into a backend as a call to an undefined %END-SYSTEM in whatever
			// package the spliced file selected. Unlike the package markers they carry no
			// state: the pruner reads them from the UNRESOLVED program (which is
			// index-aligned with the resolved copy) and drops them from its output.
			// The load-context brackets are consumed here for the same reason. They are
			// LOWERED (to assignments of *load-pathname*/*load-truename*) before this
			// pass runs, and only when the program reads either variable
			// (LispMacroExpander.lowerLoadContextMarkers); what reaches here is a
			// bracket that pass dropped -- or, in the CLI, one the macro expander
			// re-emitted verbatim on its way to that lowering.
			if (LispNames.BEGIN_SYSTEM.equals(member) || LispNames.END_SYSTEM.equals(member)
					|| LispNames.BEGIN_FILE.equals(member) || LispNames.END_FILE.equals(member)) {
				return quotedSymbol(this.currentPackage);
			}
			// The bundled-defstruct bookkeeping marker SURVIVES resolution -- unlike the
			// system brackets it carries a payload a later pass still needs: the pruner
			// splices the generated defuns ahead of its pruning, and
			// LispMacroExpander.expandTopLevelDefinitions consumes the marker to re-run
			// the expansion's registration side effects. The head stays verbatim; the
			// payload resolves like the top-level defstruct it stands for.
			if (LispNames.STRUCT_DEFINITION.equals(member) && cons.cdr() instanceof LispCons payloadCell) {
				return new LispCons(cons.car(), new LispCons(resolve(payloadCell.car()), LispNil.INSTANCE));
			}
			// A literal top-level (uiop:add-package-local-nickname 'nick 'pkg) is
			// consumed like a defpackage clause: the nickname registers here (so it
			// works on every backend -- the compiled runtimes have no uiop function)
			// and the call is replaced by its return value. A non-literal call stays a
			// runtime call, which only the interpreter can serve.
			if (LispNames.ADD_PACKAGE_LOCAL_NICKNAME.equals(member) && isUiopOperator(op)) {
				LispVal consumed = tryConsumeAddLocalNickname(cons);
				if (consumed != null) {
					return consumed;
				}
			}
			// A literal top-level (uiop:remove-package-local-nickname 'nick [scope])
			// is consumed like the add: the nickname unregisters here (so it works on
			// every backend -- the compiled runtimes have no uiop function) and the
			// call is replaced by t/nil, the runtime function's return value. A
			// non-literal call stays a runtime call, which only the interpreter can
			// serve.
			if (LispNames.REMOVE_PACKAGE_LOCAL_NICKNAME.equals(member) && isUiopOperator(op)) {
				LispVal consumed = tryConsumeRemoveLocalNickname(cons);
				if (consumed != null) {
					return consumed;
				}
			}
		}
		return resolveForm(form);
	}

	private boolean isUiopOperator(LispSymbol op) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
		return qn != null && UiopExports.isUiopFamily(this.registry.canonicalName(qn.pkg()));
	}

	/**
	 * Whether {@code op} is a package-qualified {@code define-package} of one of the two
	 * packages that define the variant: {@code uiop} or {@code mgl-pax} (nickname
	 * {@code pax}). The qualifier is required -- a bare {@code define-package} is a user
	 * symbol.
	 */
	private boolean isDefinePackageOperator(LispSymbol op) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
		if (qn == null) {
			return false;
		}
		String pkg = this.registry.canonicalName(qn.pkg());
		return UiopExports.isUiopFamily(pkg) || LispNames.MGL_PAX_PKG.equals(pkg);
	}

	private boolean isMglPaxOperator(LispSymbol op) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
		return qn != null && LispNames.MGL_PAX_PKG.equals(this.registry.canonicalName(qn.pkg()));
	}

	/**
	 * Exports the entries of a literal top-level {@code (pax:defsection NAME (:title
	 * ...) "docs..." (SYMBOL LOCATIVE) ...)} from the current package: every body list
	 * whose head is a non-keyword symbol names an entry (section references included,
	 * like real mgl-pax); docstrings and keyword-led option lists are not entries.
	 */
	private void consumeDefsectionExports(LispCons cons) {
		List<LispVal> parts = cons.toList();
		List<String> names = new ArrayList<>();
		for (LispVal part : parts.subList(Math.min(2, parts.size()), parts.size())) {
			if (part instanceof LispCons entry && entry.car() instanceof LispSymbol entrySym && !entrySym.isKeyword()) {
				// The entry is read under the current package: a bare name is that
				// package's symbol, the spelling the reader would have given it.
				names.add(PackageRegistry.splitQualified(entrySym.name()) != null || entrySym.name().startsWith("#:")
						? entrySym.name() : internSpellingOnly(entrySym.name()));
			}
		}
		if (!names.isEmpty()) {
			exportSymbols(names, this.currentPackage, true);
		}
	}

	/**
	 * Consumes a literal {@code (uiop:add-package-local-nickname 'nick 'pkg)} call:
	 * registers the (global, lite) nickname and returns the quoted target name -- the
	 * runtime function's return value. Returns null when an argument is not a literal
	 * designator (a runtime call the interpreter serves).
	 */
	private @Nullable LispVal tryConsumeAddLocalNickname(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 3 || parts.size() > 4) {
			return null;
		}
		String nickname = literalDesignator(parts.get(1));
		String actual = literalDesignator(parts.get(2));
		if (nickname == null || actual == null) {
			return null;
		}
		registerLocalNickname(nickname, actual);
		return quotedSymbol(this.registry.canonicalName(actual));
	}

	/**
	 * Consumes a literal {@code (uiop:remove-package-local-nickname 'nick [scope])} call:
	 * unregisters the (global, lite) nickname and returns t/nil -- the runtime function's
	 * return value. Returns null when an argument is not a literal designator (a runtime
	 * call the interpreter serves).
	 */
	private @Nullable LispVal tryConsumeRemoveLocalNickname(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 3) {
			return null;
		}
		String nickname = literalDesignator(parts.get(1));
		if (nickname == null) {
			return null;
		}
		String scope = null;
		if (parts.size() == 3) {
			scope = literalDesignator(parts.get(2));
			if (scope == null) {
				return null;
			}
		}
		return removeLocalNickname(nickname, scope) ? LispTrue.INSTANCE : LispNil.INSTANCE;
	}

	/**
	 * Consumes a literal top-level {@code (use-package PACKAGES [PACKAGE])} call: widens
	 * the use list of the target package (the current one by default) and returns
	 * {@code t}, the standard function's return value. Returns null when an argument is
	 * not a literal designator -- a runtime call only the interpreter can serve.
	 */
	private @Nullable LispVal tryConsumeUsePackage(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 3) {
			// Not consumable, and not this pass's error to raise: the arity belongs to
			// the FUNCTION, which signals a catchable program-error.
			return null;
		}
		List<String> used = literalDesignatorList(parts.get(1));
		String target = this.currentPackage;
		if (parts.size() == 3) {
			target = literalDesignator(parts.get(2));
		}
		if (used == null || target == null) {
			return null;
		}
		usePackage(used, target);
		return LispTrue.INSTANCE;
	}

	/**
	 * Consumes a literal top-level {@code (unuse-package PACKAGES [PACKAGE])} call: the
	 * named packages leave the target's use list and the form becomes {@code t}, the
	 * standard function's return value. Returns null when an argument is not a literal
	 * designator -- a runtime call the interpreter (and the compiled backends' prelude
	 * defun) serve instead.
	 */
	private @Nullable LispVal tryConsumeUnusePackage(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 3) {
			// Not consumable, and not this pass's error to raise: the arity belongs to
			// the FUNCTION, which signals a catchable program-error -- what
			// (signals-error (unuse-package) program-error) asks for.
			return null;
		}
		List<String> used = literalDesignatorList(parts.get(1));
		String target = this.currentPackage;
		if (parts.size() == 3) {
			target = literalDesignator(parts.get(2));
		}
		if (used == null || target == null) {
			return null;
		}
		unusePackage(used, target);
		return LispTrue.INSTANCE;
	}

	/**
	 * Consumes a literal top-level {@code (export SYMBOLS [PACKAGE])} -- or its
	 * {@code unexport} inverse -- call: rewrites the target package's external set and
	 * returns {@code t}, the standard functions' return value. Returns null when an
	 * argument is not literal (a runtime call only the interpreter can serve).
	 */
	private @Nullable LispVal tryConsumeExport(LispCons cons, boolean export) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 3) {
			return null;
		}
		String target = this.currentPackage;
		if (parts.size() == 3) {
			target = literalDesignator(parts.get(2));
		}
		if (target == null) {
			return null;
		}
		List<String> names = literalSymbolSpellings(parts.get(1), target);
		if (names == null) {
			return null;
		}
		exportSymbols(names, target, export);
		return LispTrue.INSTANCE;
	}

	/**
	 * The symbol spellings a literal SYMBOL-list argument of {@code export} /
	 * {@code unexport} / {@code import} names -- a quoted symbol or a quoted list of them
	 * -- resolved the way the reader resolved them: a bare symbol is the CURRENT
	 * package's (its accessible symbol of that name, or a fresh own one), a qualified one
	 * is what it spells. A keyword, an uninterned symbol or a string is not a symbol of
	 * any package; it names the target's own symbol, the permissive reading the
	 * name-based export always had. Null when the argument is not literal (a runtime call
	 * only the interpreter can serve).
	 */
	private @Nullable List<String> literalSymbolSpellings(LispVal arg, String targetPackage) {
		LispVal datum = arg;
		if (arg instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.cdr() instanceof LispNil) {
			datum = rest.car();
		}
		else if (!(arg instanceof LispString)) {
			return null;
		}
		List<LispVal> elements = datum instanceof LispCons list ? list.toList() : List.of(datum);
		String target = registeredPackageName(this.registry.canonicalName(targetPackage));
		List<String> spellings = new ArrayList<>(elements.size());
		for (LispVal element : elements) {
			switch (element) {
				case LispString str -> spellings.add(ownSpellingOf(target, str.value()));
				case LispSymbol sym when sym.isKeyword() ->
					spellings.add(ownSpellingOf(target, sym.name().substring(1)));
				case LispSymbol sym when sym.name().startsWith("#:") ->
					spellings.add(ownSpellingOf(target, sym.name().substring(2)));
				case LispSymbol sym -> spellings.add(PackageRegistry.splitQualified(sym.name()) != null ? sym.name()
						: internSpellingOnly(sym.name()));
				case LispTrue ignored -> spellings.add("T");
				case LispNil ignored -> spellings.add("NIL");
				default -> {
					return null;
				}
			}
		}
		return spellings;
	}

	/** The spelling of a package's own symbol, the package given as a designator. */
	private String ownSpellingOf(String pkg, String name) {
		return LispNames.CL_USER_PKG.equals(pkg) || LispNames.CL_PKG.equals(pkg) ? name
				: PackageRegistry.qualifyInternal(pkg, name);
	}

	/**
	 * Makes the named symbols external (or, with {@code export} false, internal again) in
	 * the target package -- the shared machinery behind the
	 * {@code export}/{@code unexport} directives and their interpreter-side runtime
	 * functions. A name the package does not define but INHERITS through its use list is
	 * re-exported through the same import redirect {@code defpackage}'s {@code :export}
	 * clause records, so the exported symbol stays the used package's one rather than a
	 * fresh symbol of the same name.
	 * <p>
	 * The symbol has to be accessible in the target (CLHS: a {@code package-error}
	 * otherwise): one the package owns is, whether or not the member table knows it yet
	 * -- a symbol only READ under the package is never recorded (see
	 * {@link #recordInterned}) -- so a symbol homed in the target is taken on its
	 * spelling alone, and it is symbols homed ELSEWHERE that must be imported or
	 * inherited. Two name conflicts signal the same way: a different symbol of that name
	 * already accessible in the target, and a package USING the target that has its own
	 * symbol of that name present (not shadowing). Every such failure is a
	 * {@link RuntimePackageException}, which the runtime functions turn into a catchable
	 * {@code package-error}.
	 * @param names the symbol spellings to export ({@code pkg::name} / {@code pkg:name},
	 * or a bare name for a {@code cl}/{@code cl-user} symbol; a plain STRING names the
	 * target's own symbol)
	 * @param targetPackage the package whose external set changes
	 * @param export true to export, false to unexport
	 * @throws RuntimePackageException when a symbol is not accessible in the target or
	 * the export creates a name conflict
	 */
	public void exportSymbols(List<String> names, String targetPackage, boolean export) {
		String target = registeredPackageName(this.registry.canonicalName(targetPackage));
		if (!this.registry.contains(target)) {
			throw new LispPackageException("No such package: " + targetPackage);
		}
		LispPackage pkg = this.registry.get(target);
		// Pin the spelling before the accessibility changes: from here on the package's
		// symbols keep the colon they were declared with, whichever way the external set
		// moves (see declaredExternals).
		this.declaredExternals.putIfAbsent(target, Set.copyOf(pkg.externals()));
		Set<String> externals = new HashSet<>(pkg.externals());
		Set<String> owned = new HashSet<>(pkg.symbols());
		Map<String, String> imports = new HashMap<>(pkg.imports());
		String operator = export ? LispNames.EXPORT : LispNames.UNEXPORT;
		for (String spelled : names) {
			SymbolId id = symbolId(spelled);
			String name = id.name();
			boolean foreign = id.home() != null && !id.home().equals(target);
			Accessible accessible = accessibleIn(target, name, s -> false);
			if (foreign && (accessible == null || !sameSymbol(accessible.spelling(), id))) {
				if (accessible == null) {
					throw new RuntimePackageException(operator + ": the symbol " + spelled
							+ " is not accessible in package " + target.toUpperCase(java.util.Locale.ROOT), target);
				}
				throw new RuntimePackageException(
						operator + ": the symbol " + spelled + " conflicts with " + accessible.spelling()
								+ ", which is accessible in package " + target.toUpperCase(java.util.Locale.ROOT),
						target);
			}
			if (!export) {
				// unexport leaves the symbol PRESENT, just no longer external; an
				// inherited one is not the target's to unexport and is left alone (SBCL).
				if (accessible == null || !LispNames.STATUS_INHERITED.equals(accessible.status())) {
					externals.remove(name);
				}
				continue;
			}
			SymbolId exported = new SymbolId(target, name);
			if (accessible != null && LispNames.STATUS_INHERITED.equals(accessible.status())) {
				// An inherited symbol becomes present (an import redirect to its home)
				// before it is exported -- in CL the very same symbol object, so a user
				// of the target inherits the home's symbol, not a fresh one.
				exported = symbolId(accessible.spelling());
				imports.put(name, java.util.Objects.requireNonNull(exported.home()));
			}
			else if (accessible != null && imports.containsKey(name)) {
				exported = symbolId(accessible.spelling());
			}
			for (String user : this.registry.designatorTable().values()) {
				if (user.equals(target) || !this.registry.contains(user)) {
					continue;
				}
				LispPackage using = this.registry.get(user);
				if (!using.uses(target) || using.shadows(name)) {
					continue;
				}
				Accessible present = presentIn(user, name);
				if (present != null && !sameSymbol(present.spelling(), exported)) {
					throw new RuntimePackageException(
							operator + ": the symbol " + spelled + " conflicts with " + present.spelling()
									+ ", which is present in package " + user.toUpperCase(java.util.Locale.ROOT)
									+ ", a user of " + target.toUpperCase(java.util.Locale.ROOT),
							target);
				}
			}
			externals.add(name);
			owned.add(name);
			this.registry.rehome(target, name);
		}
		this.registry.define(new LispPackage(pkg.name(), pkg.useList(), Set.copyOf(owned), Set.copyOf(externals),
				Map.copyOf(imports), pkg.shadows()));
	}

	/**
	 * A symbol's identity for the package operators: its home package (the canonical
	 * registered name; {@code keyword}; null for an uninterned symbol) and its member
	 * name. Two spellings of one symbol ({@code pkg:name} and {@code pkg::name}) have the
	 * same id, which is what the conflict checks compare.
	 */
	private record SymbolId(@Nullable String home, String name) {
	}

	private SymbolId symbolId(String spelling) {
		return new SymbolId(homeOf(spelling), LispSymbol.memberName(spelling));
	}

	private boolean sameSymbol(String spelling, SymbolId id) {
		SymbolId other = symbolId(spelling);
		return other.name().equals(id.name()) && java.util.Objects.equals(other.home(), id.home());
	}

	/**
	 * The home package a spelling names, before any {@code unintern} is considered: the
	 * qualifier of a qualified spelling (resolved to the registered name when the
	 * registry knows it), {@code keyword} for a keyword, {@code cl} for a standard name
	 * (the exported-only ones included), {@code cl-user} for any other bare name, null
	 * for an uninterned ({@code #:}) symbol.
	 */
	private @Nullable String homeOf(String spelling) {
		if (spelling.startsWith("#:")) {
			return null;
		}
		if (spelling.startsWith(":")) {
			return "keyword";
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(spelling);
		if (qn != null) {
			String found = findPackageName(qn.pkg());
			return found != null ? found : qn.pkg();
		}
		if (PackageRegistry.isClMemberName(spelling)) {
			String cl = findPackageName(LispNames.CL_PKG);
			return cl != null ? cl : LispNames.CL_PKG;
		}
		String clUser = findPackageName(LispNames.CL_USER_PKG);
		return clUser != null ? clUser : LispNames.CL_USER_PKG;
	}

	/**
	 * Imports the given symbols into the target package -- the {@code import} function
	 * and the {@code defpackage} {@code :import-from} clause's runtime twin. An import
	 * records a redirect from the member name to the symbol's home, so a bare reference
	 * in the target resolves to the home's spelling. A different symbol of the same name
	 * already PRESENT in the target (owned or imported, not merely inherited) is a name
	 * conflict and signals; the target's own symbol is a no-op, as in CL.
	 * @param spellings the symbol spellings to import
	 * @param targetPackage the package that gains the redirects
	 * @throws RuntimePackageException on a name conflict
	 */
	public void importSymbols(List<String> spellings, String targetPackage) {
		importInto(spellings, targetPackage, false);
	}

	/**
	 * Imports the given symbols into the target package SHADOWINGLY (the
	 * {@code shadowing-import} function): a present symbol of the same name is uninterned
	 * first -- an own one loses its home -- and each imported name joins the package's
	 * shadowing symbols, so it stays the accessible one whatever the use list later
	 * brings in.
	 * @param spellings the symbol spellings to import
	 * @param targetPackage the package that gains the redirects
	 */
	public void shadowingImportSymbols(List<String> spellings, String targetPackage) {
		importInto(spellings, targetPackage, true);
	}

	private void importInto(List<String> spellings, String targetPackage, boolean shadowing) {
		String target = registeredPackageName(this.registry.canonicalName(targetPackage));
		if (!this.registry.contains(target)) {
			throw new LispPackageException("No such package: " + targetPackage);
		}
		LispPackage pkg = this.registry.get(target);
		Set<String> owned = new HashSet<>(pkg.symbols());
		Set<String> externals = new HashSet<>(pkg.externals());
		Map<String, String> imports = new HashMap<>(pkg.imports());
		Set<String> shadows = new HashSet<>(pkg.shadows());
		String operator = shadowing ? LispNames.SHADOWING_IMPORT : LispNames.IMPORT;
		for (String spelled : spellings) {
			SymbolId id = symbolId(spelled);
			String name = id.name();
			if (id.home() != null && !this.registry.contains(id.home()) && !"keyword".equals(id.home())) {
				throw new LispPackageException("No such package: " + id.home());
			}
			Accessible present = presentIn(target, name);
			boolean own = id.home() == null || id.home().equals(target);
			if (present != null && !sameSymbol(present.spelling(), own ? new SymbolId(target, name) : id)) {
				if (!shadowing) {
					throw new RuntimePackageException(
							operator + ": the symbol " + spelled + " conflicts with " + present.spelling()
									+ ", which is present in package " + target.toUpperCase(java.util.Locale.ROOT),
							target);
				}
				// shadowing-import uninterns the present symbol: an own one loses its
				// home, an imported one just its redirect; either way it stops being
				// external.
				if (!imports.containsKey(name)) {
					this.registry.markUnhomed(target, name);
				}
				owned.remove(name);
				imports.remove(name);
				externals.remove(name);
			}
			if (own) {
				// The package's own symbol (or an uninterned one, which CL homes here:
				// a spelling cannot change its home, so it becomes an own symbol).
				owned.add(name);
				this.registry.rehome(target, name);
			}
			else {
				imports.put(name, trueHome(java.util.Objects.requireNonNull(id.home()), name));
			}
			if (shadowing) {
				shadows.add(name);
			}
		}
		this.registry.define(new LispPackage(pkg.name(), pkg.useList(), Set.copyOf(owned), Set.copyOf(externals),
				Map.copyOf(imports), Set.copyOf(shadows)));
	}

	/**
	 * Makes the named symbols shadowing symbols of the target package (the {@code shadow}
	 * function): a name already present (owned or imported) is marked as is, any other is
	 * interned as a fresh own symbol first.
	 * @param names the symbol names
	 * @param targetPackage the package whose shadowing set grows
	 */
	public void shadowSymbols(List<String> names, String targetPackage) {
		String target = registeredPackageName(this.registry.canonicalName(targetPackage));
		if (!this.registry.contains(target)) {
			throw new LispPackageException("No such package: " + targetPackage);
		}
		LispPackage pkg = this.registry.get(target);
		Set<String> owned = new HashSet<>(pkg.symbols());
		Set<String> shadows = new HashSet<>(pkg.shadows());
		for (String name : names) {
			if (!pkg.imports().containsKey(name)) {
				if (!owned.contains(name) || this.registry.isUnhomed(target, name)) {
					this.registry.markRecorded(target, name);
				}
				owned.add(name);
				this.registry.rehome(target, name);
			}
			shadows.add(name);
		}
		this.registry.define(new LispPackage(pkg.name(), pkg.useList(), Set.copyOf(owned), pkg.externals(),
				pkg.imports(), Set.copyOf(shadows)));
	}

	/**
	 * Removes a symbol from the target package's member table (the {@code unintern}
	 * function): the package's own symbol loses its home
	 * ({@link PackageRegistry#markUnhomed}), an imported one only its redirect; either
	 * way it stops being external and shadowing. A symbol not present in the target --
	 * inherited, or homed elsewhere and not imported -- is left alone and answers false.
	 * Uninterning a shadowing symbol that hid two DIFFERENT inherited symbols of that
	 * name would leave a name conflict, so it signals instead and changes nothing.
	 * @param spelling the symbol spelling
	 * @param targetPackage the package to remove it from
	 * @return whether the symbol was present and has been removed
	 * @throws RuntimePackageException when the removal would uncover a name conflict
	 */
	public boolean uninternSymbol(String spelling, String targetPackage) {
		String target = registeredPackageName(this.registry.canonicalName(targetPackage));
		if (!this.registry.contains(target)) {
			throw new LispPackageException("No such package: " + targetPackage);
		}
		LispPackage pkg = this.registry.get(target);
		SymbolId id = symbolId(spelling);
		String name = id.name();
		String importHome = pkg.imports().get(name);
		boolean present;
		if (importHome != null) {
			present = id.home() != null && sameSymbol(homeSpelling(importHome, name), id);
		}
		else {
			// The package's own symbol, whether or not the table records it (a read-time
			// symbol is present in CL too).
			present = id.home() != null && id.home().equals(target) && !this.registry.isUnhomed(target, name);
		}
		if (!present) {
			return false;
		}
		if (pkg.shadows(name)) {
			Set<String> uncovered = new LinkedHashSet<>();
			for (String used : pkg.useList()) {
				Accessible inherited = inheritedFrom(used, name);
				if (inherited != null) {
					SymbolId candidate = symbolId(inherited.spelling());
					uncovered.add(candidate.home() + "::" + candidate.name());
				}
			}
			if (uncovered.size() > 1) {
				throw new RuntimePackageException(LispNames.UNINTERN + ": uninterning " + spelling + " from package "
						+ target.toUpperCase(java.util.Locale.ROOT) + " would leave a name conflict between "
						+ String.join(" and ", uncovered), target);
			}
		}
		Set<String> owned = new HashSet<>(pkg.symbols());
		Set<String> externals = new HashSet<>(pkg.externals());
		Map<String, String> imports = new HashMap<>(pkg.imports());
		Set<String> shadows = new HashSet<>(pkg.shadows());
		owned.remove(name);
		externals.remove(name);
		imports.remove(name);
		shadows.remove(name);
		if (importHome == null) {
			this.registry.markUnhomed(target, name);
			this.registry.unrecord(target, name);
		}
		this.registry.define(new LispPackage(pkg.name(), pkg.useList(), Set.copyOf(owned), Set.copyOf(externals),
				Map.copyOf(imports), Set.copyOf(shadows)));
		return true;
	}

	/**
	 * The shadowing symbols of a package (the {@code package-shadowing-symbols}
	 * function), as canonical spellings in name order: the {@code defpackage}
	 * {@code :shadow} / {@code :shadowing-import-from} names and the runtime
	 * {@code shadow} / {@code shadowing-import} ones, each spelled as the symbol the
	 * package makes accessible under it.
	 * @param packageDesignator the package name as given (any case, nickname allowed)
	 * @return the shadowing symbols' spellings
	 * @throws LispPackageException when no such package exists
	 */
	public List<String> shadowingSymbols(String packageDesignator) {
		String pkg = findPackageName(packageDesignator);
		if (pkg == null) {
			throw new LispPackageException("No such package: " + packageDesignator);
		}
		if ("keyword".equals(pkg)) {
			return List.of();
		}
		LispPackage p = this.registry.get(pkg);
		List<String> names = new ArrayList<>(p.shadows());
		java.util.Collections.sort(names);
		List<String> out = new ArrayList<>(names.size());
		for (String name : names) {
			String home = p.imports().get(name);
			out.add(home != null ? homeSpelling(home, name) : ownSpelling(pkg, name));
		}
		return out;
	}

	/**
	 * Consumes a literal top-level {@code (import SYMBOLS [PACKAGE])} call: records the
	 * import redirects in the target package and returns {@code t}, the standard
	 * function's return value. Returns null when an argument is not literal (a runtime
	 * call only the interpreter can serve).
	 */
	private @Nullable LispVal tryConsumeImport(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 3) {
			return null;
		}
		String target = this.currentPackage;
		if (parts.size() == 3) {
			target = literalDesignator(parts.get(2));
		}
		if (target == null) {
			return null;
		}
		List<String> names = literalSymbolSpellings(parts.get(1), target);
		if (names == null) {
			return null;
		}
		importSymbols(names, target);
		return LispTrue.INSTANCE;
	}

	/**
	 * A literal package-designator LIST: a single designator, or a quoted list of them
	 * ({@code (use-package '(:a :b))} -- {@code use-package} takes a designator or a list
	 * of designators). Null when any element is not literal.
	 */
	private static @Nullable List<String> literalDesignatorList(LispVal arg) {
		LispVal datum = LispNil.INSTANCE;
		if (arg instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.cdr() instanceof LispNil) {
			// Only a QUOTED list is a designator list; an unquoted one is a call that
			// computes the argument, which this pass cannot see through.
			datum = rest.car();
		}
		if (datum instanceof LispCons list) {
			List<String> names = new ArrayList<>();
			for (LispVal element : list.toList()) {
				// The elements of a QUOTED list are data: a bare symbol names a package
				// there, so re-wrap it as the quoted shape literalDesignator accepts.
				String name = literalDesignator(element instanceof LispSymbol sym && !sym.isKeyword()
						? new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(sym, LispNil.INSTANCE)) : element);
				if (name == null) {
					return null;
				}
				names.add(name);
			}
			return names;
		}
		String single = literalDesignator(arg);
		return single == null ? null : List.of(single);
	}

	/**
	 * Adds the named packages to the use list of the target package -- the shared
	 * machinery behind the {@code use-package} directive and its interpreter-side runtime
	 * function. Only EXTERNAL symbols of a used package become visible unqualified (see
	 * {@link #resolveUnqualified}), and a package that is already used is a no-op, as in
	 * Common Lisp.
	 * @param used the package names to use (any case, nicknames allowed)
	 * @param targetPackage the package whose use list grows
	 */
	public void usePackage(List<String> used, String targetPackage) {
		String target = registeredPackageName(this.registry.canonicalName(targetPackage));
		if (!this.registry.contains(target)) {
			throw new LispPackageException("No such package: " + targetPackage);
		}
		LispPackage pkg = this.registry.get(target);
		List<String> useList = new ArrayList<>(pkg.useList());
		for (String name : used) {
			String canonical = registeredPackageName(this.registry.canonicalName(name));
			if (!this.registry.contains(canonical)) {
				throw new LispPackageException("No such package: " + name);
			}
			// A package always sees its own symbols; CL rejects using a package in
			// itself, and so does the equivalent shape here.
			if (canonical.equals(target)) {
				throw new LispPackageException("Cannot " + LispNames.USE_PACKAGE + " " + target + " in itself");
			}
			for (String use : withImpliedUses(canonical)) {
				if (!useList.contains(use)) {
					useList.add(use);
				}
			}
		}
		this.registry.define(new LispPackage(pkg.name(), List.copyOf(useList), pkg.symbols(), pkg.externals(),
				pkg.imports(), pkg.shadows()));
	}

	/**
	 * Removes the named packages from the use list of the target package -- the
	 * {@code unuse-package} mirror of {@link #usePackage}, shared by the directive and
	 * its runtime function. A package that is not used is a no-op, as in Common Lisp; an
	 * unknown designator signals, like every other package operator here.
	 * @param used the package names to stop using (any case, nicknames allowed)
	 * @param targetPackage the package whose use list shrinks
	 */
	public void unusePackage(List<String> used, String targetPackage) {
		String target = registeredPackageName(this.registry.canonicalName(targetPackage));
		if (!this.registry.contains(target)) {
			throw new LispPackageException("No such package: " + targetPackage);
		}
		LispPackage pkg = this.registry.get(target);
		List<String> useList = new ArrayList<>(pkg.useList());
		for (String name : used) {
			String canonical = registeredPackageName(this.registry.canonicalName(name));
			if (!this.registry.contains(canonical)) {
				throw new LispPackageException("No such package: " + name);
			}
			// The implied uses go with the package that implied them (a use of
			// closer-common-lisp brought cl along), so the removal drops the same set
			// the addition added.
			useList.removeAll(withImpliedUses(canonical));
		}
		this.registry.define(new LispPackage(pkg.name(), List.copyOf(useList), pkg.symbols(), pkg.externals(),
				pkg.imports(), pkg.shadows()));
	}

	/**
	 * The use-list entries a use of the given package implies, the package itself first.
	 * {@code closer-common-lisp} is a flat re-export of the WHOLE {@code cl} package
	 * (overlaid with {@code closer-mop}), so using it must make the cl symbols visible
	 * unqualified exactly as {@code (:use :cl)} would -- and cl visibility is judged by a
	 * DIRECT use ({@link #currentUsesCl}, {@link #resolveQualified}'s inherited-cl
	 * branch), so the implication is recorded in the use list itself.
	 */
	private static List<String> withImpliedUses(String canonical) {
		if (LispNames.CLOSER_COMMON_LISP_PKG.equals(canonical)) {
			return List.of(canonical, LispNames.CL_PKG);
		}
		return List.of(canonical);
	}

	/** A literal package designator: a string, keyword/#: symbol, or quoted symbol. */
	private static @Nullable String literalDesignator(LispVal arg) {
		LispVal datum = arg;
		if (arg instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.cdr() instanceof LispNil) {
			datum = rest.car();
		}
		return switch (datum) {
			case LispString str -> str.value();
			case LispSymbol sym -> sym.name().startsWith("#:") ? sym.name().substring(2)
					: sym.name().startsWith(":") ? sym.name().substring(1) : datum == arg ? null : sym.name();
			default -> null;
		};
	}

	/**
	 * The current package name, as tracked across the forms resolved so far.
	 * @return the current package name
	 */
	public String currentPackageName() {
		return this.currentPackage;
	}

	/**
	 * Sets the current package to the given (canonicalized) name -- the runtime half of a
	 * {@code (let ((*package* X)) ...)} rebinding: the interpreter's {@code evalLet}
	 * swaps the package for the binding's extent so a macro-time {@code (intern ...)}
	 * under the binding homes where CL would. The caller restores the saved name.
	 * @param name the package name (any case, nickname allowed)
	 */
	public void setCurrentPackage(String name) {
		String canonical = registeredPackageName(
				this.registry.canonicalName(PackageRegistry.canonicalBuiltinName(name)));
		this.currentPackage = canonical;
	}

	/**
	 * Saves the current package on the internal stack. Called by the runtime {@code load}
	 * machinery (and via the {@code %push-package} marker on the compile path) before
	 * descending into a loaded file, so the file's internal {@code in-package} is scoped
	 * to the load and does not leak to the caller -- mirroring Common Lisp binding
	 * {@code *package*} for the duration of {@code load}.
	 */
	public void pushPackage() {
		this.packageStack.addLast(this.currentPackage);
	}

	/**
	 * Restores the package saved by the matching {@link #pushPackage}. A pop without a
	 * prior push leaves the current package unchanged (defensive: the marker pairs are
	 * always balanced in practice).
	 */
	public void popPackage() {
		if (!this.packageStack.isEmpty()) {
			this.currentPackage = this.packageStack.removeLast();
		}
	}

	/**
	 * How many packages {@link #pushPackage} has saved and no {@link #popPackage} has
	 * restored yet.
	 * @return the depth of the saved-package stack
	 */
	public int packageStackDepth() {
		return this.packageStack.size();
	}

	/**
	 * Puts the saved-package stack and the current package back to what they were: what
	 * an evaluation that could not run its own restores (a stack overflow) leaves for the
	 * caller to undo.
	 * @param depth the depth {@link #packageStackDepth} answered then
	 * @param current the current package name then
	 */
	public void restorePackageState(int depth, String current) {
		while (this.packageStack.size() > depth) {
			this.packageStack.removeLast();
		}
		this.currentPackage = current;
	}

	private LispVal resolveInPackage(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new LispPackageException(LispNames.IN_PACKAGE + " expects exactly one argument");
		}
		String name = registeredPackageName(
				this.registry.canonicalName(packageDesignator(LispNames.IN_PACKAGE, parts.get(1))));
		if (!this.registry.contains(name)) {
			throw new LispPackageException("No such package: " + name);
		}
		this.currentPackage = name;
		return packageAssignment(name);
	}

	/**
	 * The runtime half of a package switch: {@code (setq *package* :NAME)}. Common Lisp's
	 * {@code *package*} is a dynamic variable read when a form RUNS, so a defun that
	 * reads it (rove's {@code set-test}, alexandria's {@code maybe-intern}) must see the
	 * package current at CALL time, not the one this pass had current when the defun was
	 * resolved. Every top-level {@code in-package} (and the {@code %pop-package} restore
	 * of a spliced load) therefore assigns the run-time variable too; top-level forms run
	 * in resolution order, so the two states agree at every top-level point. The value is
	 * the package KEYWORD -- the same object {@code find-package} answers, so
	 * {@code (eq *package* (find-package ...))} holds and a hash keyed on packages works.
	 * The compile paths inject the {@code (defvar *package* :cl-user)} default only when
	 * the program READS the variable, and drop these assignments otherwise
	 * ({@code LispMacroExpander.injectMvSpillGlobal}); the interpreter's {@code setq} of
	 * {@code *package*} writes straight through to this resolver's current package.
	 */
	private static LispVal packageAssignment(String name) {
		return new LispCons(new LispSymbol(LispNames.SETQ), new LispCons(new LispSymbol(LispNames.PACKAGE_VAR),
				new LispCons(new LispSymbol(":" + name), LispNil.INSTANCE)));
	}

	/**
	 * Defines a package from a literal, top-level {@code (defpackage NAME
	 * (:use ...) (:export ...))} directive -- or, when one of that name already exists,
	 * MODIFIES it, merging the clauses into what is there (CLHS 11.1.2.1). Exported names
	 * become owned and external; symbols interned later (defuns under
	 * {@code (in-package NAME)}, free variables) are internal. Without a {@code :use}
	 * clause nothing is visible unqualified (like SBCL), so {@code (:use :cl)} must be
	 * spelled out. {@code :nicknames} registers alternate package names,
	 * {@code :import-from} maps the named symbols to their source package (resolution is
	 * textual, so an imported name simply resolves to the source package's canonical
	 * spelling), and {@code :documentation}/{@code :size} are accepted and ignored.
	 * {@code :shadow} records the named symbols so their unqualified uses inside the
	 * package resolve package-locally (see {@link #resolveUnqualified}).
	 * {@code :shadowing-import-from} (and any other clause) is an error.
	 */
	// The registered spelling of a package name: the exact spelling when registered,
	// else its lowercase twin when THAT is registered (an internal lowercase-authored
	// registration -- a shim leaf-module package -- referenced from upcase-read
	// source), else the exact spelling (the caller's not-found error names it).
	private String registeredPackageName(String name) {
		if (this.registry.contains(name)) {
			return name;
		}
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		if (!lower.equals(name) && this.registry.contains(lower)) {
			return lower;
		}
		return name;
	}

	// Whether the named package provides (owns or exports) the given symbol name --
	// the :import-from fold's oracle. cl is answered by the static symbol set, so
	// car/cdr compositions count too.
	private boolean sourceProvides(String packageName, String symbolName) {
		if (LispNames.CL_PKG.equals(packageName)) {
			return PackageRegistry.isClSymbol(symbolName);
		}
		LispPackage pkg = this.registry.get(packageName);
		return pkg.owns(symbolName) || pkg.exports(symbolName);
	}

	private LispVal resolveDefpackage(LispCons cons) {
		return resolveDefpackage(cons, false);
	}

	/**
	 * Resolves a {@code defpackage} -- or, with {@code definePackageVariant} true, a
	 * consumed {@code uiop:define-package}/{@code mgl-pax:define-package} -- form. The
	 * variant additionally accepts {@code :use-reexport}; its other extra clauses stay
	 * unsupported until a consumer needs them.
	 */
	private LispVal resolveDefpackage(LispCons cons, boolean definePackageVariant) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new LispPackageException(LispNames.DEFPACKAGE + " expects a package name");
		}
		String name = designator(LispNames.DEFPACKAGE, "a package name", parts.get(1));
		validateDefpackageShape(parts.subList(2, parts.size()), definePackageVariant);
		// CLHS 11.1.2.1: defpackage over an EXISTING package modifies it -- the new
		// definition is merged into what is already there rather than replacing it or
		// signalling. That is what lets a library declare a package rontolisp has
		// pre-seeded (upstream cl+ssl's own (defpackage :cl+ssl ...) over the seeded
		// CL+SSL, .kb/cffi.md) and what makes a file re-read in one session idempotent.
		// A name that is a NICKNAME of some other package is still refused: adjusting
		// through it would silently apply the definition to a package of a different
		// name.
		LispPackage existing = null;
		if (this.registry.contains(name)) {
			if (!this.registry.canonicalName(name).equals(name)) {
				throw DefpackageException.packageError("Package already exists: " + name, name);
			}
			existing = this.registry.get(name);
		}
		List<String> useList = existing == null ? new ArrayList<>() : new ArrayList<>(existing.useList());
		Set<String> exports = existing == null ? new HashSet<>() : new HashSet<>(existing.externals());
		List<String> nicknames = new ArrayList<>();
		Map<String, String> imports = existing == null ? new HashMap<>() : new HashMap<>(existing.imports());
		Map<String, String> shadowingImports = new HashMap<>();
		Set<String> shadows = existing == null ? new HashSet<>() : new HashSet<>(existing.shadows());
		Set<String> interned = new HashSet<>();
		for (LispVal clause : parts.subList(2, parts.size())) {
			if (!(clause instanceof LispCons clauseCons) || !(clauseCons.car() instanceof LispSymbol keyword)
					|| !keyword.isKeyword()) {
				throw new LispPackageException(
						LispNames.DEFPACKAGE + " expects (:use ...) / (:export ...) clauses, got " + clause.print());
			}
			List<LispVal> args = clauseCons.toList();
			switch (keyword.name()) {
				case LispNames.USE_KEYWORD -> {
					for (LispVal arg : args.subList(1, args.size())) {
						String used = registeredPackageName(
								this.registry.canonicalName(designator(LispNames.USE_KEYWORD, "a package name", arg)));
						if (!this.registry.contains(used)) {
							throw DefpackageException.packageError("No such package: " + used, used);
						}
						for (String use : withImpliedUses(used)) {
							if (!useList.contains(use)) {
								useList.add(use);
							}
						}
					}
				}
				case LispNames.EXPORT_KEYWORD -> {
					for (LispVal arg : args.subList(1, args.size())) {
						exports.add(designator(LispNames.EXPORT_KEYWORD, "a symbol name", arg));
					}
				}
				case ":USE-REEXPORT" -> {
					// define-package only (dbi's package header): use the packages AND
					// re-export their external symbols. The exports ride the same
					// import-redirect the export-of-an-inherited-name pass below records,
					// so each re-exported name stays the used package's symbol.
					for (LispVal arg : args.subList(1, args.size())) {
						String used = registeredPackageName(
								this.registry.canonicalName(designator(":use-reexport", "a package name", arg)));
						if (!this.registry.contains(used)) {
							throw DefpackageException.packageError("No such package: " + used, used);
						}
						for (String use : withImpliedUses(used)) {
							if (!useList.contains(use)) {
								useList.add(use);
							}
						}
						exports.addAll(this.registry.get(used).externals());
					}
				}
				case LispNames.NICKNAMES_KEYWORD -> {
					for (LispVal arg : args.subList(1, args.size())) {
						String nickname = designator(LispNames.NICKNAMES_KEYWORD, "a package name", arg);
						// A nickname this same package already answers to is a
						// re-declaration, not a collision (a modifying defpackage
						// repeats its own :nicknames clause).
						if (this.registry.contains(nickname) && !this.registry.canonicalName(nickname).equals(name)) {
							throw DefpackageException.packageError("Package already exists: " + nickname, name);
						}
						nicknames.add(nickname);
					}
				}
				case LispNames.LOCAL_NICKNAMES_KEYWORD -> {
					// (:local-nicknames (nickname actual-package)...) -- lite: registered
					// as a GLOBAL nickname (no per-package scoping), like
					// uiop:add-package-local-nickname. The target must already exist.
					for (LispVal arg : args.subList(1, args.size())) {
						if (!(arg instanceof LispCons pair) || pair.toList().size() != 2) {
							throw new LispPackageException(LispNames.LOCAL_NICKNAMES_KEYWORD
									+ " expects (nickname actual-package) pairs, got " + arg.print());
						}
						registerLocalNickname(
								designator(LispNames.LOCAL_NICKNAMES_KEYWORD, "a package name", pair.toList().get(0)),
								designator(LispNames.LOCAL_NICKNAMES_KEYWORD, "a package name", pair.toList().get(1)));
					}
				}
				case LispNames.IMPORT_FROM_KEYWORD -> collectImportFrom(LispNames.IMPORT_FROM_KEYWORD, args, imports);
				// (:shadowing-import-from PKG name...): the import map is the FIRST
				// thing resolveUnqualified consults -- before the shadow set, the cl
				// symbol table and the use list -- so a recorded import already has
				// exactly the always-wins precedence CL gives a shadowing import. The
				// clauses share one collector; shadowing entries are merged LAST, so
				// they beat a plain :import-from of the same name (dbd-postgres takes
				// database-error-message/-code from cl-postgres-error over the ones its
				// use list inherits from dbi.error).
				case ":SHADOWING-IMPORT-FROM" -> collectImportFrom(":shadowing-import-from", args, shadowingImports);
				// (:intern name...): the names become the package's OWN symbols
				// without being external -- the pkg::name spelling reaches them and
				// pkg:name does not. Resolution here is textual, so owning a name is
				// exactly what the clause has to record.
				case ":INTERN" -> {
					for (LispVal arg : args.subList(1, args.size())) {
						interned.add(designator(":intern", "a symbol name", arg));
					}
				}
				// Metadata: accepted for portability, not recorded anywhere.
				case ":DOCUMENTATION", ":SIZE" -> {
				}
				case ":SHADOW" -> {
					// (:shadow name...): inside this package the names always resolve to
					// the package's own symbols, so a library can redefine a cl name
					// (cl-ppcre shadows digit-char-p and defconstant).
					for (LispVal arg : args.subList(1, args.size())) {
						String shadowed = designator(":shadow", "a symbol name", arg);
						// The designator reads upcased while a shadowed CL name's folded
						// references are lowercase ((:shadow #:defconstant) must catch
						// the bare defconstant the reader folds); non-CL names stay as
						// spelled, matching their equally-upcased references.
						String shadowedLower = shadowed.toLowerCase(java.util.Locale.ROOT);
						shadows.add(PackageRegistry.isClSymbol(shadowedLower) ? shadowedLower : shadowed);
					}
				}
				default -> throw DefpackageException
					.programError("Unsupported " + LispNames.DEFPACKAGE + " clause: " + keyword.name());
			}
		}
		// Shadowing imports win over plain imports of the same name (their whole
		// point) -- and they are shadowing symbols, so package-shadowing-symbols lists
		// them (the import map is consulted before the shadow set, so resolution does
		// not change).
		imports.putAll(shadowingImports);
		shadows.addAll(shadowingImports.keySet());
		// An :export of a name the package does not define but INHERITS through its use
		// list is a re-export of the used package's symbol -- in Common Lisp the very
		// same symbol object, so postmodern's (:use :s-sql) + (:export #:sql) exports
		// S-SQL:SQL rather than minting a POSTMODERN:SQL of its own. Resolution here is
		// textual, so record it as an import: both resolveUnqualified and
		// resolveQualified already redirect through that map to the source package.
		// (A re-exported cl symbol needs no entry -- the isClSymbol branch of
		// resolveUnqualified runs before the owns() check and yields the bare name.)
		for (String exported : exports) {
			if (shadows.contains(exported) || imports.containsKey(exported) || PackageRegistry.isClSymbol(exported)) {
				continue;
			}
			for (String used : useList) {
				if (!LispNames.CL_PKG.equals(used) && this.registry.get(used).exports(exported)) {
					imports.put(exported, trueHome(used, exported));
					break;
				}
			}
		}
		// CLHS defpackage: :intern runs AFTER :use, and "finds or creates" -- a name some
		// used package exports is found there (inherited), not minted as an own symbol.
		interned.removeIf(internedName -> useList.stream().anyMatch(used -> inheritedFrom(used, internedName) != null));
		Set<String> owned = new HashSet<>(exports);
		owned.addAll(shadows);
		owned.addAll(interned);
		if (existing != null) {
			owned.addAll(existing.symbols());
		}
		this.registry.define(new LispPackage(name, List.copyOf(useList), Set.copyOf(owned), Set.copyOf(exports),
				Map.copyOf(imports), Set.copyOf(shadows)));
		for (String nickname : nicknames) {
			this.registry.defineNickname(nickname, name);
		}
		// Tier (see .kb/packages.md, "Runtime tier"): a defpackage the COMPILE path
		// resolves mints a read/compile-time package, because the backends bake its
		// spellings and deleting or renaming it would orphan them. The interpreter
		// resolves against the LIVE registry -- there is nothing baked -- so its
		// defpackage products join the runtime tier and delete-package/rename-package
		// accept them, which is what a program that defines a package, uses it and
		// tears it down again expects. A defpackage MODIFYING an existing package keeps
		// whatever tier that package already had.
		if (!this.inProgramResolution && existing == null) {
			this.registry.markRuntimePackage(name);
		}
		if (existing == null) {
			this.registry.markSealed(name);
		}
		// The value is the package, which at run time is its keyword -- the same object
		// make-package and find-package answer, so the three compare eq.
		return new LispSymbol(":" + name.toUpperCase(java.util.Locale.ROOT));
	}

	/**
	 * The {@code defpackage} checks CLHS puts on the FORM, before any package is looked
	 * up (SBCL makes them at macroexpansion): every option is a known keyword clause,
	 * {@code :size} and {@code :documentation} appear at most once with one argument, and
	 * the names of {@code :shadow}, {@code :shadowing-import-from}, {@code :import-from}
	 * and {@code :intern} are pairwise disjoint, as are those of {@code :intern} and
	 * {@code :export}. Each violation is a {@code program-error}.
	 */
	private static void validateDefpackageShape(List<LispVal> clauses, boolean definePackageVariant) {
		Map<String, Set<String>> names = new HashMap<>();
		Set<String> singletons = new HashSet<>();
		for (LispVal clause : clauses) {
			if (!(clause instanceof LispCons clauseCons) || !(clauseCons.car() instanceof LispSymbol keyword)
					|| !keyword.isKeyword()) {
				throw DefpackageException.programError(
						LispNames.DEFPACKAGE + " expects (:use ...) / (:export ...) clauses, got " + clause.print());
			}
			List<LispVal> args = clauseCons.toList();
			String option = keyword.name();
			switch (option) {
				case ":SIZE", ":DOCUMENTATION" -> {
					if (!singletons.add(option)) {
						throw DefpackageException
							.programError(LispNames.DEFPACKAGE + ": can't specify " + option + " more than once");
					}
					if (args.size() != 2) {
						throw DefpackageException.programError(LispNames.DEFPACKAGE + ": " + option
								+ " expects a single argument, got " + clause.print());
					}
				}
				case ":SHADOW", ":INTERN", LispNames.EXPORT_KEYWORD -> collectNames(names, option, args, 1);
				case LispNames.IMPORT_FROM_KEYWORD, ":SHADOWING-IMPORT-FROM" -> collectNames(names, option, args, 2);
				case LispNames.USE_KEYWORD, LispNames.NICKNAMES_KEYWORD, LispNames.LOCAL_NICKNAMES_KEYWORD -> {
				}
				case ":USE-REEXPORT" -> {
					if (!definePackageVariant) {
						throw DefpackageException
							.programError("Unsupported " + LispNames.DEFPACKAGE + " clause: " + option);
					}
				}
				default -> throw DefpackageException
					.programError("Unsupported " + LispNames.DEFPACKAGE + " clause: " + option);
			}
		}
		String[][] disjoint = { { ":SHADOW", ":SHADOWING-IMPORT-FROM" }, { ":SHADOW", LispNames.IMPORT_FROM_KEYWORD },
				{ ":SHADOW", ":INTERN" }, { ":SHADOWING-IMPORT-FROM", LispNames.IMPORT_FROM_KEYWORD },
				{ ":SHADOWING-IMPORT-FROM", ":INTERN" }, { LispNames.IMPORT_FROM_KEYWORD, ":INTERN" },
				{ ":INTERN", LispNames.EXPORT_KEYWORD } };
		for (String[] pair : disjoint) {
			Set<String> common = new java.util.TreeSet<>(names.getOrDefault(pair[0], Set.of()));
			common.retainAll(names.getOrDefault(pair[1], Set.of()));
			if (!common.isEmpty()) {
				throw DefpackageException.programError(LispNames.DEFPACKAGE + ": parameters " + pair[0] + " and "
						+ pair[1] + " must be disjoint but have common elements " + common);
			}
		}
	}

	// The symbol names of one clause (from argument index `from`), keyed by option.
	private static void collectNames(Map<String, Set<String>> names, String option, List<LispVal> args, int from) {
		Set<String> into = names.computeIfAbsent(option, k -> new HashSet<>());
		for (LispVal arg : args.subList(Math.min(from, args.size()), args.size())) {
			into.add(designator(option.toLowerCase(java.util.Locale.ROOT), "a symbol name", arg));
		}
	}

	private static String packageDesignator(String context, LispVal designator) {
		return designator(context, "a package name", designator);
	}

	/**
	 * Collects an {@code (:import-from PKG name...)} / {@code (:shadowing-import-from
	 * PKG name...)} clause's names into {@code target} as import redirects to the source
	 * package.
	 */
	private void collectImportFrom(String clauseName, List<LispVal> args, Map<String, String> target) {
		if (args.size() < 2) {
			throw new LispPackageException(clauseName + " expects a package name");
		}
		String source = registeredPackageName(
				this.registry.canonicalName(designator(clauseName, "a package name", args.get(1))));
		if (!this.registry.contains(source)) {
			throw DefpackageException.packageError("No such package: " + source, source);
		}
		for (LispVal arg : args.subList(2, args.size())) {
			String member = designator(clauseName, "a symbol name", arg);
			// The upcase reader premise upcases the designator while a built-in source
			// package's canonical spellings are lowercase: fold when the lowercase
			// spelling is the one the source actually provides ((:import-from #:cl
			// #:car) imports car).
			String lower = member.toLowerCase(java.util.Locale.ROOT);
			if (!member.equals(lower) && sourceProvides(source, lower) && !sourceProvides(source, member)) {
				member = lower;
			}
			// Only a SEALED source answers "no such symbol": one source was read into
			// may hold names the reader interned and the table never saw.
			if (this.registry.isSealed(source) && accessibleIn(source, member, spelling -> false) == null) {
				throw DefpackageException.missingSymbol(clauseName + ": no symbol named " + member + " in " + source,
						source, member);
			}
			target.put(member, trueHome(source, member));
		}
	}

	/**
	 * Registers a package nickname -- the shared machinery behind the {@code defpackage}
	 * {@code :local-nicknames} clause and {@code uiop:add-package-local-nickname}. Lite:
	 * the nickname is GLOBAL (rontolisp has no per-package nickname scoping), so a
	 * nickname that names an existing package (or one already taken for a different
	 * target) is rejected.
	 * @param nickname the nickname as written (prefix already stripped)
	 * @param actual the target package name as written
	 */
	public void registerLocalNickname(String nickname, String actual) {
		String target = this.registry.canonicalName(actual);
		if (!this.registry.contains(target)) {
			throw new LispPackageException("No such package: " + actual);
		}
		String existing = this.registry.canonicalName(nickname);
		if (this.registry.contains(nickname)) {
			throw new LispPackageException("Package already exists: " + nickname);
		}
		if (!existing.equals(nickname) && !existing.equals(target)) {
			throw new LispPackageException(
					"Nickname " + nickname + " already names " + existing + "; cannot repoint it to " + target);
		}
		this.registry.defineNickname(nickname, target);
	}

	/**
	 * Removes a package nickname -- the runtime half of
	 * {@code uiop:remove-package-local-nickname}. Lite: nicknames are GLOBAL (no
	 * per-package scoping, see {@link #registerLocalNickname}), so the scope package only
	 * guards the removal: when given, the nickname must point at it, otherwise nothing is
	 * removed. The scope is canonicalized exactly like a registration target, so it
	 * compares equal to what {@link #registerLocalNickname} stored.
	 * @param nickname the nickname as written (prefix already stripped)
	 * @param scopeDesignator the scope package as written, or null for no guard
	 * @return {@code true} when a mapping was removed
	 * @throws LispPackageException when the scope names no package
	 */
	public boolean removeLocalNickname(String nickname, @Nullable String scopeDesignator) {
		if (!this.registry.contains(nickname) || this.registry.canonicalName(nickname).equals(nickname)) {
			// Not a nickname mapping at all (unknown, or a package's own name):
			// nothing to remove.
			return false;
		}
		if (scopeDesignator != null) {
			if (findPackageName(scopeDesignator) == null) {
				throw new LispPackageException("No such package: " + scopeDesignator);
			}
			if (!this.registry.canonicalName(nickname).equals(this.registry.canonicalName(scopeDesignator))) {
				return false;
			}
		}
		return this.registry.removeNickname(nickname);
	}

	/**
	 * Creates a runtime-tier package (the {@code make-package} operator): an empty
	 * package with the given use list and nicknames, registered under the upcased name
	 * (the reader-canonical rule -- a runtime name behaves like a read one). The
	 * {@code :use} entries must name packages the registry already knows; a name or
	 * nickname colliding with any registered designator is refused, like
	 * {@code defpackage}'s.
	 * @param name the package name as given (any case)
	 * @param use the use-list entries as given (any case, nicknames allowed)
	 * @param nicknames the nicknames as given (any case)
	 * @return the canonical (upcased) package name
	 * @throws RuntimePackageException when the name exists, a use entry is unknown, or a
	 * nickname collides
	 */
	public String createRuntimePackage(String name, java.util.List<String> use, java.util.List<String> nicknames) {
		String canonical = name.toUpperCase(java.util.Locale.ROOT);
		if (canonical.isEmpty()) {
			throw new RuntimePackageException("MAKE-PACKAGE expects a package name", name);
		}
		if (this.registry.contains(canonical)) {
			throw new RuntimePackageException("MAKE-PACKAGE: package already exists: " + canonical, name);
		}
		java.util.List<String> useList = new java.util.ArrayList<>();
		for (String entry : use) {
			String used = registeredPackageName(this.registry.canonicalName(entry));
			if (!this.registry.contains(used)) {
				throw new RuntimePackageException("MAKE-PACKAGE: no such package: " + entry, entry);
			}
			for (String implied : withImpliedUses(used)) {
				if (!useList.contains(implied)) {
					useList.add(implied);
				}
			}
		}
		java.util.List<String> canonicalNicknames = new java.util.ArrayList<>();
		for (String nickname : nicknames) {
			String nick = nickname.toUpperCase(java.util.Locale.ROOT);
			if (this.registry.contains(nick)) {
				throw new RuntimePackageException("MAKE-PACKAGE: package already exists: " + nick, nickname);
			}
			canonicalNicknames.add(nick);
		}
		this.registry
			.define(new LispPackage(canonical, java.util.List.copyOf(useList), java.util.Set.of(), java.util.Set.of()));
		for (String nick : canonicalNicknames) {
			this.registry.defineNickname(nick, canonical);
		}
		this.registry.markRuntimePackage(canonical);
		this.registry.markSealed(canonical);
		return canonical;
	}

	/**
	 * Deletes a runtime-tier package (the {@code delete-package} operator): the
	 * registration and its nicknames are dropped. Read/compile-time packages (built-ins
	 * and {@code defpackage} products) are immutable -- every backend resolved against
	 * them -- and an unknown designator names nothing to delete.
	 * @param designator the package designator as given (any case, nickname allowed)
	 * @return the deleted canonical package name
	 * @throws RuntimePackageException when no package answers or it is not runtime-tier
	 */
	public String deleteRuntimePackage(String designator) {
		String pkg = findPackageName(designator);
		if (pkg == null) {
			throw new RuntimePackageException("DELETE-PACKAGE: no such package: " + designator, designator);
		}
		String canonical = registeredPackageName(pkg);
		if (!this.registry.isRuntimePackage(canonical)) {
			throw new RuntimePackageException("DELETE-PACKAGE: cannot delete read/compile-time package: "
					+ canonical.toUpperCase(java.util.Locale.ROOT), designator);
		}
		this.registry.remove(canonical);
		return canonical;
	}

	/**
	 * Renames a runtime-tier package (the {@code rename-package} operator), replacing its
	 * nicknames with {@code newNicknames} (Common Lisp's replace rule -- the old
	 * nicknames are dropped even when the new list is empty).
	 * @param designator the package designator as given (any case, nickname allowed)
	 * @param newName the new package name as given (any case)
	 * @param newNicknames the replacement nicknames as given (any case)
	 * @return the new canonical package name
	 * @throws RuntimePackageException when no package answers, it is not runtime-tier, or
	 * the new name (or a nickname) collides with a different package
	 */
	public String renameRuntimePackage(String designator, String newName, java.util.List<String> newNicknames) {
		String pkg = findPackageName(designator);
		if (pkg == null) {
			throw new RuntimePackageException("RENAME-PACKAGE: no such package: " + designator, designator);
		}
		String canonical = registeredPackageName(pkg);
		if (!this.registry.isRuntimePackage(canonical)) {
			throw new RuntimePackageException("RENAME-PACKAGE: cannot rename read/compile-time package: "
					+ canonical.toUpperCase(java.util.Locale.ROOT), designator);
		}
		String renamed = newName.toUpperCase(java.util.Locale.ROOT);
		if (renamed.isEmpty()) {
			throw new RuntimePackageException("RENAME-PACKAGE expects a new package name", newName);
		}
		if (!renamed.equals(canonical) && this.registry.contains(renamed)) {
			throw new RuntimePackageException("RENAME-PACKAGE: package already exists: " + renamed, newName);
		}
		java.util.List<String> canonicalNicknames = new java.util.ArrayList<>();
		for (String nickname : newNicknames) {
			String nick = nickname.toUpperCase(java.util.Locale.ROOT);
			String target = this.registry.canonicalName(nick);
			if (this.registry.contains(nick) && !target.equals(canonical)) {
				throw new RuntimePackageException("RENAME-PACKAGE: package already exists: " + nick, nickname);
			}
			canonicalNicknames.add(nick);
		}
		this.registry.rename(canonical, renamed, canonicalNicknames);
		return renamed;
	}

	/**
	 * The nickname strings of a designated package (the {@code package-nicknames}
	 * operator), in a deterministic order.
	 * @param designator the package designator as given (any case, nickname allowed)
	 * @return the nicknames as spelled at registration
	 * @throws RuntimePackageException when no package answers
	 */
	public java.util.List<String> runtimePackageNicknames(String designator) {
		String pkg = findPackageName(designator);
		if (pkg == null) {
			throw new RuntimePackageException("PACKAGE-NICKNAMES: no such package: " + designator, designator);
		}
		return this.registry.nicknamesFor(registeredPackageName(pkg));
	}

	private static String designator(String context, String kind, LispVal designator) {
		return switch (designator) {
			// A keyword (:cl-user), an uninterned symbol (#:cl-user, the common
			// defpackage idiom) or a bare symbol (cl-user); strip the prefix.
			case LispSymbol sym -> sym.name().startsWith("#:") ? sym.name().substring(2)
					: sym.isKeyword() ? sym.name().substring(1) : sym.name();
			case LispString str -> str.value();
			// A CHARACTER is a string designator too (CLHS glossary), so #\A names the
			// package -- or the symbol -- "A"; the defpackage tests spell every clause
			// that way at least once.
			case LispChar ch -> ch.display();
			default -> throw new LispPackageException(context + " expects " + kind + ", got " + designator.print());
		};
	}

	private LispVal resolveForm(LispVal form) {
		return switch (form) {
			case LispSymbol sym -> resolveSymbol(sym);
			case LispCons cons -> resolveCons(cons);
			case LispStructLiteral literal -> resolveStructLiteralType(literal);
			default -> form;
		};
	}

	/**
	 * Resolves the TYPE NAME of a {@code #S(...)} literal, and nothing else. The type
	 * name sits in a genuine symbol position -- Common Lisp reads it in the current
	 * package -- so resolving it here is what makes {@code #S(PT :X 1)} inside a package
	 * find the {@code (defstruct pt ...)} of that same package: both spellings go through
	 * this resolver exactly once and end up canonically equal. The slot names are matched
	 * by base name and the slot values are DATA, so both are left untouched, exactly as
	 * the datum of a {@code quote} is.
	 */
	private LispVal resolveStructLiteralType(LispStructLiteral literal) {
		if (!(resolveSymbol(new LispSymbol(literal.typeName())) instanceof LispSymbol resolved)
				|| resolved.name().equals(literal.typeName())) {
			return literal;
		}
		return new LispStructLiteral(resolved.name(), literal.slotNames(), literal.slotValues());
	}

	/**
	 * Resolves a {@code case}/{@code ecase}/{@code ccase} clause head: a lone symbol key
	 * through {@link #resolveSymbol} and a key LIST element-wise the same way (non-symbol
	 * keys -- numbers, characters, keywords -- stay as read).
	 */
	private LispVal resolveCaseKeys(LispVal key) {
		if (key instanceof LispSymbol keySym) {
			return resolveSymbol(keySym);
		}
		if (key instanceof LispCons keyList) {
			List<LispVal> resolved = new ArrayList<>();
			LispVal tail = keyList;
			while (tail instanceof LispCons cell) {
				resolved.add(cell.car() instanceof LispSymbol el ? resolveSymbol(el) : cell.car());
				tail = cell.cdr();
			}
			return SourceProvenance.inherit(keyList, LispCons.rebuiltList(keyList, resolved));
		}
		return key;
	}

	private LispVal resolveCons(LispCons cons) {
		if (cons.car() instanceof LispSymbol op && LispNames.QUOTE.equals(operatorMember(op))
				&& cons.cdr() instanceof LispCons datumCons && datumCons.cdr() instanceof LispNil) {
			// Only the well-formed (quote DATUM) shape is the special form; a
			// one-element (quote) or longer list is data in some other position -- a
			// lambda list or let binding whose variable is NAMED quote (s-sql's :copy
			// op binds a `quote` group via split-on-keywords) -- and falls through to
			// the generic walk.
			LispVal datum = datumCons.car();
			// (quote DATUM): the operator is exempt, and every SYMBOL inside the datum
			// resolves against the current package -- exactly what Common Lisp's reader
			// does when it interns the datum's symbols at read time. That covers the
			// backquote templates of a defmacro/macrolet (the reader has already
			// expanded every backquote level into list/cons/quote calls by now), a
			// '%indicator in a defun body (ironclad's defdigest writes plist entries
			// under template-resolved indicators that digestp reads back via a body
			// quote), and a quoted DATA TABLE whose symbols name functions or macros:
			// postmodern's *result-styles* is a defparameter list of (:rows
			// list-row-reader all-rows) triples that its `query` macro splices into the
			// expansion, so ALL-ROWS has to be the same POSTMODERN::ALL-ROWS the
			// defmacro registers. The one exemption is host-facing data (a WIT type, an
			// export field name), which never names a Lisp symbol.
			if (this.inMacroDefinition || !this.inHostFacingData) {
				datum = resolveQuotedData(datum);
			}
			// Identity-preserving when neither the datum nor the operator's spelling
			// changed (see the generic walk below for why that matters).
			if (datum == datumCons.car() && LispNames.QUOTE.equals(op.name())) {
				return cons;
			}
			return SourceProvenance.inherit(cons,
					new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(datum, LispNil.INSTANCE)));
		}
		if (cons.car() instanceof LispSymbol rawOp && LispNames.DEFPACKAGE.equals(operatorMember(rawOp))) {
			// resolveCons only sees non-top-level forms (resolve() consumes the
			// top-level directive), so this defpackage runs when its enclosing form
			// runs: the interpreter registers it then, in the RUNTIME tier, and the
			// clauses are literal data that must reach that registration as written --
			// hence verbatim, with no walk into them. The compiled backends have no
			// registry to register into and refuse the form where they meet it
			// (Jvm/WasmExprCompiler), which is where the old hard error moved to.
			return cons;
		}
		if (cons.car() instanceof LispSymbol macroOp && (LispNames.DEFMACRO.equals(operatorMember(macroOp))
				|| LispNames.DEFINE_COMPILER_MACRO.equals(operatorMember(macroOp))
				|| LispNames.DEFINE_SETF_EXPANDER.equals(operatorMember(macroOp))
				|| LispNames.DEFSETF.equals(operatorMember(macroOp)))) {
			// The whole definition is template context (see the quote case above): a
			// define-compiler-macro / define-setf-expander / defsetf body builds forms
			// with backquote just like a defmacro, so its template symbols (the function
			// the rewritten call names, an internal helper the store form calls) belong
			// to the defining package, not the call site's.
			boolean saved = this.inMacroDefinition;
			this.inMacroDefinition = true;
			try {
				return SourceProvenance.inherit(cons,
						LispCons.rebuilt(cons, resolveForm(cons.car()), resolveForm(cons.cdr())));
			}
			finally {
				this.inMacroDefinition = saved;
			}
		}
		if (cons.car() instanceof LispSymbol macroletOp && LispNames.MACROLET.equals(operatorMember(macroletOp))
				&& cons.cdr() instanceof LispCons defsCell) {
			// (macrolet ((name lambda-list body...)...) body...): the local
			// definitions are template context, the outer body is ordinary code.
			boolean saved = this.inMacroDefinition;
			this.inMacroDefinition = true;
			LispVal defs;
			try {
				defs = resolveForm(defsCell.car());
			}
			finally {
				this.inMacroDefinition = saved;
			}
			return SourceProvenance.inherit(cons, LispCons.rebuilt(cons, resolveForm(cons.car()),
					LispCons.rebuilt(defsCell, defs, resolveForm(defsCell.cdr()))));
		}
		if (cons.car() instanceof LispSymbol caseOp && (LispNames.CASE.equals(operatorMember(caseOp))
				|| LispNames.ECASE.equals(operatorMember(caseOp)) || LispNames.CCASE.equals(operatorMember(caseOp)))
				&& cons.cdr() instanceof LispCons caseRest) {
			// (case KEYFORM (KEYS body...)...): the KEYS are unevaluated data. A clause
			// head spelled `quote` is a KEY like any other -- NOT the quote special form
			// -- so the clause body must still resolve as code (s-sql's
			// expand-table-name dispatches on `(case (car name) (quote (concatenate
			// ...)))`; the generic walk would treat that clause as quoted data and leave
			// its variable references unresolved). Keys resolve like quoted data: a lone
			// symbol through resolveSymbol (so it stays eql to a data symbol), and a key
			// LIST element-wise the same way (sxql's define-op dispatches (ecase
			// struct-type ((unary-op ...) ...)) where struct-type holds the imported
			// SXQL/SQL-TYPE:UNARY-OP; an unresolved key list never matches it).
			List<LispVal> resolvedParts = new ArrayList<>();
			resolvedParts.add(resolveForm(cons.car()));
			resolvedParts.add(resolveForm(caseRest.car()));
			LispVal clauses = caseRest.cdr();
			while (clauses instanceof LispCons clauseCell) {
				if (clauseCell.car() instanceof LispCons clauseCons) {
					List<LispVal> newClause = new ArrayList<>();
					LispVal key = clauseCons.car();
					newClause.add(resolveCaseKeys(key));
					LispVal body = clauseCons.cdr();
					while (body instanceof LispCons bodyCell) {
						newClause.add(resolveForm(bodyCell.car()));
						body = bodyCell.cdr();
					}
					resolvedParts
						.add(SourceProvenance.inherit(clauseCons, LispCons.rebuiltList(clauseCons, newClause)));
				}
				else {
					resolvedParts.add(resolveForm(clauseCell.car()));
				}
				clauses = clauseCell.cdr();
			}
			return SourceProvenance.inherit(cons, LispCons.rebuiltList(cons, resolvedParts));
		}
		if (cons.car() instanceof LispSymbol findPkgOp && LispNames.FIND_PACKAGE.equals(operatorMember(findPkgOp))
				&& cons.cdr() instanceof LispCons argCell && argCell.cdr() instanceof LispNil) {
			// (find-package LITERAL) folds here, the one place with the registry: the
			// "package value" is the upcased canonical name as a keyword (nil when
			// unknown), so a literal call answers identically on every backend -- the
			// compiled runtimes have no package registry. A computed designator stays a
			// call, which only the interpreter can serve.
			String designator = literalDesignator(argCell.car());
			if (designator != null) {
				String found = findPackageName(designator);
				// ... unless the program can create packages at run time: an unknown
				// name may come into being later, so the call stays a call and the
				// backends answer it from their baked table plus the runtime table.
				// A KNOWN name still folds -- read/compile-time packages are
				// immutable at run time, so the answer cannot change. A lone form
				// resolved outside resolveProgram (the interpreter resolves each
				// top-level form just before evaluating it) never folds an unknown
				// name: a creation may run between this resolution and a later call
				// through the form (a defun body), and only a call sees it.
				boolean mutable = this.runtimePackagesMutable || !this.inProgramResolution
						|| referencesRuntimePackageMutation(cons, false);
				if (found != null || !mutable) {
					return SourceProvenance.inherit(cons, found == null ? LispNil.INSTANCE
							: quotedSymbol(":" + found.toUpperCase(java.util.Locale.ROOT)));
				}
			}
		}
		LispVal car = resolveForm(cons.car());
		if (car instanceof LispSymbol op) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
			if (qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg())) {
				if (LispNames.WASM_IMPORT.equals(qn.member()) || LispNames.WASM_EXPORT.equals(qn.member())) {
					return resolveWasmDirective(op, cons);
				}
				if (LispNames.WIT_EXPORT.equals(qn.member()) || LispNames.WIT_IMPORT.equals(qn.member())
						|| LispNames.COMPONENT_IMPORT.equals(qn.member())) {
					// Every argument is data the directive reads itself (a WIT file path,
					// the :world / :interface / :package keywords and names in the WIT's
					// own spelling), so nothing inside resolves as a Lisp variable or
					// function. A wit-import is consumed by WitImportInliner before this
					// resolver runs on the compile path; the interpreter evaluates it as
					// a
					// special form, and in both cases the names it BINDS are canonical
					// already.
					return SourceProvenance.inherit(cons, LispCons.rebuilt(cons, op, cons.cdr()));
				}
			}
		}
		// Walk the argument tail ELEMENT-WISE, never re-reading a tail as a form of its
		// own: a variable named `quote` followed by exactly one argument -- s-sql's
		// :copy op binds a `quote` group, so its body has (when quote (sql-expand
		// ...)) -- would otherwise make the tail look like (quote DATUM) and leave the
		// following argument unresolved.
		List<LispVal> rest = new ArrayList<>();
		boolean changed = car != cons.car();
		LispVal tail = cons.cdr();
		while (tail instanceof LispCons tailCons) {
			LispVal element = resolveForm(tailCons.car());
			changed |= element != tailCons.car();
			rest.add(element);
			tail = tailCons.cdr();
		}
		LispVal result = tail instanceof LispNil ? LispNil.INSTANCE : resolveForm(tail);
		changed |= result != tail;
		// A form nothing resolved differently is handed back AS IT WAS READ, cons
		// identity included: this resolver runs over every form of every program, and
		// SourceProvenance keys a form's source position on that identity, so rebuilding
		// an unchanged form would erase the position of the entire program in the one
		// case (a file with no package qualification anywhere) where every position is
		// otherwise known. It also stops the pass allocating a copy of the whole AST for
		// nothing.
		if (!changed) {
			return cons;
		}
		for (int i = rest.size() - 1; i >= 0; i--) {
			result = new LispCons(rest.get(i), result);
		}
		// The other half of the rule: a form something DID resolve differently is a
		// genuine REWRITE, and every cons from here down to that symbol is a fresh key
		// in the identity-keyed table. The rewritten form stands for the same source
		// text, so it takes the original's position. Otherwise every form of every file
		// that says (in-package :foo) and then names anything qualified -- the whole of
		// every quickloaded library -- reports with no position at all.
		return SourceProvenance.inherit(cons, new LispCons(car, result));
	}

	/**
	 * Resolves a {@code rontolisp:wasm-import}/{@code rontolisp:wasm-export} directive.
	 * The quoted first argument names a Lisp function -- the synthetic defun an import
	 * creates, or the existing defun an export wraps -- so unlike ordinary quoted data it
	 * is package-scoped and resolves like a defun name against the current package (a
	 * canonical qualified name re-resolves to itself). The remaining options resolve
	 * normally: the {@code :params} keyword list and a lenient quoted-symbol {@code :as}
	 * alias stay untouched under the quote exemption.
	 */
	private LispVal resolveWasmDirective(LispSymbol op, LispCons cons) {
		// Only the FIRST argument names a Lisp function; everything after it is
		// host-facing data (the export field name, the WIT parameter types), so its
		// quoted symbols stay verbatim.
		boolean saved = this.inHostFacingData;
		try {
			if (cons.cdr() instanceof LispCons nameCell) {
				LispVal nameArg = nameCell.car();
				LispVal resolvedName;
				if (nameArg instanceof LispCons quoted && quoted.car() instanceof LispSymbol quoteOp
						&& LispNames.QUOTE.equals(operatorMember(quoteOp)) && quoted.cdr() instanceof LispCons datumCell
						&& datumCell.car() instanceof LispSymbol nameSym && !nameSym.isKeyword()) {
					resolvedName = SourceProvenance.inherit(quoted, new LispCons(new LispSymbol(LispNames.QUOTE),
							new LispCons(resolveSymbol(nameSym), LispNil.INSTANCE)));
				}
				else {
					resolvedName = resolveForm(nameArg);
				}
				this.inHostFacingData = true;
				return SourceProvenance.inherit(cons, LispCons.rebuilt(cons, op,
						LispCons.rebuilt(nameCell, resolvedName, resolveForm(nameCell.cdr()))));
			}
			this.inHostFacingData = true;
			return SourceProvenance.inherit(cons, LispCons.rebuilt(cons, op, resolveForm(cons.cdr())));
		}
		finally {
			this.inHostFacingData = saved;
		}
	}

	// Resolves every symbol inside a quoted datum (recursively through conses);
	// non-symbol atoms pass through. Data position is more permissive than code
	// position -- Common Lisp's reader simply INTERNS what it reads, so a name that
	// would be an error to call here is still a perfectly good symbol (see
	// this.inQuotedData).
	private LispVal resolveQuotedData(LispVal datum) {
		boolean saved = this.inQuotedData;
		this.inQuotedData = true;
		try {
			return resolveQuotedDatum(datum);
		}
		finally {
			this.inQuotedData = saved;
		}
	}

	private LispVal resolveQuotedDatum(LispVal datum) {
		return switch (datum) {
			case LispSymbol sym -> resolveSymbol(sym);
			// Identity-preserving, and inheriting when it is not. A datum whose symbols
			// all resolve to themselves is EVERY quoted list of an ordinary cl-user
			// file, and rebuilding one used to make its (quote ...) form -- and every
			// ancestor of that -- a fresh cons the provenance table has never seen.
			case LispCons c -> LispTrees.rebuildSpine(c,
					node -> node instanceof LispCons ? null : resolveQuotedDatum(node), this::resolveQuotedDatum,
					(cell, car, cdr) -> SourceProvenance.inherit(cell, LispCons.rebuilt(cell, car, cdr)));
			default -> datum;
		};
	}

	private LispVal resolveSymbol(LispSymbol sym) {
		LispVal resolved = resolveSymbolName(sym);
		// A name that resolves to itself hands back the symbol AS READ. Symbols carry no
		// identity in this implementation (there is no intern table -- see
		// .kb/symbol-runtime-api.md), so a fresh copy is indistinguishable in behavior;
		// what it is NOT indistinguishable in is the cons rebuild it forces on the
		// enclosing form, which erases that form's SourceProvenance position.
		return resolved instanceof LispSymbol out && out.name().equals(sym.name()) ? sym : resolved;
	}

	private LispVal resolveSymbolName(LispSymbol sym) {
		if (sym.isKeyword()) {
			return sym;
		}
		// Lambda-list keywords (&rest, &optional, &key, ...) are structural markers,
		// not package-scoped symbols; they pass through like keywords.
		if (sym.name().startsWith("&")) {
			return sym;
		}
		// An uninterned symbol (#:foo, or a gensym-produced #:g1) belongs to no
		// package; it passes through like a keyword.
		if (sym.name().startsWith("#:")) {
			return sym;
		}
		// A struct instance tag ('%struct-PKG::NAME, baked into defstruct-generated
		// defun bodies) is an internal token over an ALREADY-canonical struct name, not
		// a package-scoped symbol -- its embedded :: must not read as a package
		// qualifier. It reaches this pass only when generated defuns ride through a
		// re-resolution, i.e. the pruner's bundled-defstruct early splice.
		if (sym.name().startsWith(LispLayout.STRUCT_TAG_PREFIX)) {
			return sym;
		}
		String name = sym.name();
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		if (qn != null) {
			return resolveQualified(qn);
		}
		return resolveUnqualified(name);
	}

	private LispVal resolveQualified(PackageRegistry.QualifiedName qn) {
		String pkg = registeredPackageName(this.registry.canonicalName(qn.pkg()));
		String member = qn.member();
		if (!this.registry.contains(pkg)) {
			throw new LispPackageException("No such package: " + pkg);
		}
		// The reader upcases user spellings while a package's canonical members may be
		// lowercase (a wit-import package's defuns derive from the WIT's lower-kebab
		// names): retry the lowercase spelling before judging externality.
		String lower = member.toLowerCase(java.util.Locale.ROOT);
		if (!this.exactCase && !lower.equals(member) && !providesMember(pkg, member) && providesMember(pkg, lower)
				&& !this.registry.isRecorded(pkg, lower)) {
			member = lower;
		}
		// A single colon only reaches external (exported) symbols, like Common Lisp; a
		// double colon reaches (and interns) any symbol.
		if (!qn.internal() && !isExternal(pkg, member)) {
			throw new LispPackageException("The symbol " + member + " is not external in the " + pkg + " package (use "
					+ PackageRegistry.qualifyInternal(pkg, member) + ")");
		}
		// A symbol imported via :import-from lives in its source package; resolution is
		// textual, so redirect to the source package's canonical spelling.
		String importSource = this.registry.get(pkg).imports().get(member);
		if (importSource != null) {
			pkg = importSource;
		}
		// In CL, pkg::name reaches any symbol ACCESSIBLE in pkg -- including one
		// inherited from cl -- so `cl-postgres::write-string` IS `cl:write-string`
		// (s-sql's to-s-sql-string spells it that way). A member the package neither
		// owns, exports, shadows, nor imports, but inherits from a used cl, resolves
		// to the bare canonical CL name instead of minting a distinct internal symbol.
		if (!LispNames.CL_PKG.equals(pkg) && !LispNames.CL_USER_PKG.equals(pkg)) {
			LispPackage p = this.registry.get(pkg);
			if (p.uses(LispNames.CL_PKG) && !p.owns(member) && !p.exports(member) && !p.shadows(member)
					&& PackageRegistry.isClSymbol(member)) {
				return new LispSymbol(member);
			}
		}
		// cl and cl-user are normalized to bare names; other packages keep the qualified
		// canonical name.
		if (LispNames.CL_PKG.equals(pkg) || LispNames.CL_USER_PKG.equals(pkg)) {
			return new LispSymbol(member);
		}
		if (!this.exactCase && !providesMember(pkg, member)) {
			// Source minted a name the table does not hold (see PackageRegistry.sealed).
			this.registry.unseal(pkg);
		}
		return canonical(pkg, member);
	}

	// Whether the package provides (owns, exports or imports) the member under this
	// exact spelling -- the case-fold retry's oracle in resolveQualified.
	private boolean providesMember(String pkg, String member) {
		LispPackage p = this.registry.get(pkg);
		return p.owns(member) || p.exports(member) || p.imports().containsKey(member);
	}

	private boolean isExternal(String pkg, String member) {
		if (LispNames.CL_PKG.equals(pkg) && LispNames.isCarCdrComposition(member)) {
			return true;
		}
		return this.registry.get(pkg).exports(member);
	}

	/**
	 * Whether the symbol is SPELLED with one colon -- the identity question, decided by
	 * the package's DECLARED external set rather than by its current accessibility. The
	 * two differ only once a runtime {@code export}/{@code unexport} directive has moved
	 * the external set of that package (see {@link #declaredExternals}).
	 */
	private boolean spellsExternal(String pkg, String member) {
		if (LispNames.CL_PKG.equals(pkg) && LispNames.isCarCdrComposition(member)) {
			return true;
		}
		Set<String> declared = this.declaredExternals.get(pkg);
		return declared != null ? declared.contains(member) : this.registry.get(pkg).exports(member);
	}

	/**
	 * Whether {@code member} is external in {@code pkg} -- i.e. whether this resolver
	 * spells that symbol with ONE colon. A macro expansion that SYNTHESIZES a name (the
	 * only one is {@code defstruct}: its constructor/predicate/copier/accessors are
	 * derived from the struct name, not written down) must ask, because the name it emits
	 * has to be the same string a call site resolves to. Reading it wrong is not a
	 * package error but an undefined function: an interpreted {@code (quri:uri-p x)} used
	 * to look for {@code QURI.URI:URI-P} while the {@code defstruct} had defined
	 * {@code QURI.URI::URI-P}.
	 * @param pkg the canonical package name
	 * @param member the unqualified symbol name
	 * @return {@code true} when the package exports the name
	 */
	public boolean spellsAsExternal(String pkg, String member) {
		return this.registry.contains(pkg) && spellsExternal(pkg, member);
	}

	private LispVal resolveUnqualified(String name) {
		// *package* is an ordinary cl variable here -- read at RUN time, never folded to
		// the package this pass has current (see packageAssignment) -- so it takes the
		// generic cl-symbol path below: bare when the current package uses cl, else the
		// undefined-symbol error every unqualified cl name gets there.
		LispPackage current = this.registry.get(this.currentPackage);
		// A symbol imported via :import-from resolves to its source package's canonical
		// spelling. Checked before the cl branch so (:import-from :cl :car) works in a
		// package that does not use cl.
		String importSource = current.imports().get(name);
		if (importSource != null) {
			if (LispNames.CL_PKG.equals(importSource) || LispNames.CL_USER_PKG.equals(importSource)) {
				return new LispSymbol(name);
			}
			return canonical(importSource, name);
		}
		// A shadowed name always resolves to the current package's own symbol, never to
		// the cl (or any used package's) symbol of the same name. Checked before the cl
		// branch: shadowing a cl name is the whole point of the :shadow clause.
		if (current.shadows(name)) {
			return canonical(this.currentPackage, name);
		}
		if (PackageRegistry.isClSymbol(name)) {
			if (currentUsesCl()) {
				return new LispSymbol(name);
			}
			// In DATA position the name is not a call, so there is nothing to reject:
			// the reader interns it in the current package, exactly like any other
			// unknown name below.
			if (this.inQuotedData) {
				unsealCurrent();
				return canonical(this.currentPackage, name);
			}
			throw new LispPackageException(
					"Undefined symbol: " + name + " (use " + PackageRegistry.qualify(LispNames.CL_PKG, name) + ")");
		}
		if (current.owns(name)) {
			return canonical(this.currentPackage, name);
		}
		for (String used : current.useList()) {
			if (LispNames.CL_PKG.equals(used)) {
				continue;
			}
			// Using a package makes only its external (exported) symbols accessible,
			// like Common Lisp; internal symbols still require the double colon.
			LispSymbol viaUsed = usedExport(used, name);
			if (viaUsed != null) {
				return viaUsed;
			}
		}
		// The reader upcases user spellings, but a wit-import package's members are
		// lower-kebab WIT labels (create-shader, not CREATE-SHADER), so a bare reference
		// under (in-package :gl) retries its lowercase spelling against the current
		// package and the use list before being interned as a fresh symbol.
		// Both retries reach DECLARED members only: a name the runtime intern minted is
		// verbatim and never a reader-case mismatch (PackageRegistry.isRecorded).
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		if (!this.exactCase && !lower.equals(name)) {
			if ((current.owns(lower) || current.exports(lower))
					&& !this.registry.isRecorded(this.currentPackage, lower)) {
				return canonical(this.currentPackage, lower);
			}
			for (String used : current.useList()) {
				if (!LispNames.CL_PKG.equals(used) && !this.registry.isRecorded(used, lower)) {
					LispSymbol viaUsed = usedExport(used, lower);
					if (viaUsed != null) {
						return viaUsed;
					}
				}
			}
		}
		// The mirror image: `name` itself already arrives lower-kebab, from AST someone
		// injected rather than from source text, while the surrounding package was
		// declared with an ordinary hand-written `defpackage` whose `:export` clause IS
		// read through the normal upcasing reader (gl.lisp exports CREATE-SHADER). The
		// lower-kebab retry above never fires there -- lower.equals(name) is already
		// true -- so without this the definition resolves to the internal, lowercase
		// GL::create-shader while every call site resolves to the external
		// GL:CREATE-SHADER: the function compiles under one name and is called under
		// another (undefined-function, silently downgraded to a WASM call-time-error
		// stub that traps at runtime instead of failing to compile).
		//
		// It is a safety net, NOT the wit-import path: a package-less binding is named
		// the READER's spelling of its WIT label by WitImportDirective itself
		// (.kb/wit.md). Reconciling the two spellings here can only work for a name the
		// package DECLARES, and gl.lisp's unexported `fail` declared none -- it matched
		// neither retry, and the whole error path behind it was tree-shaken out of every
		// browser demo without a word.
		String upper = name.toUpperCase(java.util.Locale.ROOT);
		if (!this.exactCase && !upper.equals(name)) {
			if ((current.owns(upper) || current.exports(upper))
					&& !this.registry.isRecorded(this.currentPackage, upper)) {
				return canonical(this.currentPackage, upper);
			}
			for (String used : current.useList()) {
				if (!LispNames.CL_PKG.equals(used) && !this.registry.isRecorded(used, upper)) {
					LispSymbol viaUsed = usedExport(used, upper);
					if (viaUsed != null) {
						return viaUsed;
					}
				}
			}
		}
		// Unknown symbol: a user definition or forward reference in the current package.
		unsealCurrent();
		return canonical(this.currentPackage, name);
	}

	// Source minted an unrecorded name in the current package (see
	// PackageRegistry.sealed); a runtime intern or find-symbol probe (exactCase) does
	// not: the first records what it mints, the second mints nothing in CL.
	private void unsealCurrent() {
		if (!this.exactCase) {
			this.registry.unseal(this.currentPackage);
		}
	}

	/**
	 * The canonical spelling of an EXPORTED member of a used package, or null when the
	 * package does not export it. A re-exported member (recorded in the package's import
	 * map: a {@code defpackage} {@code :export} of a used package's symbol, or the
	 * {@code closer-common-lisp} overlay) redirects to its HOME package -- resolution is
	 * textual, so spelling it under the re-exporting package would name a different
	 * function.
	 */
	private @Nullable LispSymbol usedExport(String used, String name) {
		LispPackage pkg = this.registry.get(used);
		if (!pkg.exports(name)) {
			return null;
		}
		String home = pkg.imports().get(name);
		if (home == null) {
			return canonical(used, name);
		}
		if (LispNames.CL_PKG.equals(home) || LispNames.CL_USER_PKG.equals(home)) {
			return new LispSymbol(name);
		}
		return canonical(home, name);
	}

	/**
	 * The package a member actually lives in, following the source package's own import
	 * map: a package that provides a name only as a redirect (a re-export chain, or the
	 * {@code closer-common-lisp} overlay) is not the member's home, and recording it
	 * would spell references under a package that names no definition. Every recorded
	 * import already points at a true home (this method guards each recording site), so a
	 * single hop suffices.
	 * @param source the package the member was named through
	 * @param member the unqualified symbol name
	 * @return the member's home package
	 */
	private String trueHome(String source, String member) {
		LispPackage pkg = this.registry.get(source);
		if (pkg == null) {
			return source;
		}
		String home = pkg.imports().get(member);
		if (home != null) {
			return home;
		}
		// A name the source package only INHERITS through its use list: CL's import
		// works on any ACCESSIBLE symbol (find-symbol semantics), so an (:import-from
		// #:mito.class #:table-column-references-column) -- where mito.class merely
		// uses mito.class.column and does not re-export the name -- must reach the
		// exporting package's symbol, not mint a mito.class-internal one.
		if (!pkg.symbols().contains(member) && !PackageRegistry.isClSymbol(member)) {
			for (String used : pkg.useList()) {
				if (!LispNames.CL_PKG.equals(used)) {
					LispPackage usedPkg = this.registry.get(used);
					if (usedPkg != null && usedPkg.exports(member)) {
						return trueHome(used, member);
					}
				}
			}
		}
		return source;
	}

	private boolean currentUsesCl() {
		if (LispNames.CL_PKG.equals(this.currentPackage)) {
			return true;
		}
		LispPackage current = this.registry.get(this.currentPackage);
		return current != null && current.uses(LispNames.CL_PKG);
	}

	/**
	 * The canonical spelling of a resolved symbol: bare for {@code cl-user}, and
	 * qualified otherwise -- single colon for an external symbol, double colon for an
	 * internal one (so the canonical form re-resolves to itself). "External" here is the
	 * DECLARED external set ({@link #spellsExternal}): a symbol keeps one spelling for
	 * the whole program, whichever way a runtime {@code export}/{@code unexport} later
	 * moves the package's accessibility.
	 */
	private LispSymbol canonical(String pkg, String name) {
		if (LispNames.CL_USER_PKG.equals(pkg)) {
			return new LispSymbol(name);
		}
		if (spellsExternal(pkg, name)) {
			return new LispSymbol(PackageRegistry.qualify(pkg, name));
		}
		return new LispSymbol(PackageRegistry.qualifyInternal(pkg, name));
	}

	/**
	 * The spelling {@code intern} gives a bare name in the current package (CL's
	 * {@code *package*} semantics): an accessible symbol keeps its canonical home
	 * spelling (bare for {@code cl}/{@code cl-user} symbols, qualified otherwise); an
	 * unknown name is interned into the current package. This is what makes a macro-time
	 * {@code (intern (concatenate ...))} under {@code (in-package p)} name the same
	 * function as a literal {@code defun} in that file.
	 * <p>
	 * A name that is ALREADY a package-qualified canonical spelling ({@code PKG:NAME} /
	 * {@code PKG::NAME} for a package the registry knows) names the symbol it spells --
	 * it is not a fresh symbol of that whole string in the current package. A symbol IS
	 * its canonical spelling here, so a runtime string that carries a qualifier (the type
	 * name {@code type-of} peels off a {@code %class-} tag, say) round-trips instead of
	 * coming back doubly qualified as {@code APP::LIB:WIDGET}; that is also what the
	 * package-blind {@code intern} of the compile paths does.
	 * @param name the bare name to intern
	 * @return the canonical spelling for the current package
	 */
	public String internSpelling(String name) {
		return internSpelling(name, false);
	}

	/**
	 * As {@link #internSpelling(String)}, and with {@code record} true the runtime
	 * {@code intern} itself: a name the current package did not make accessible is
	 * RECORDED as its own symbol ({@link #recordInterned}), so {@code find-symbol}
	 * answers it from then on. A spelling computed for any other purpose (the
	 * {@code find-symbol} probe, a printer question) passes false and leaves the member
	 * table alone.
	 * @param name the bare name to intern
	 * @param record whether to record a freshly minted name in its package
	 * @return the canonical spelling for the current package
	 */
	public String internSpelling(String name, boolean record) {
		String spelling = internSpellingOnly(name);
		if (record) {
			recordInterned(spelling);
		}
		return spelling;
	}

	private String internSpellingOnly(String name) {
		boolean savedExactCase = this.exactCase;
		this.exactCase = true;
		try {
			return internSpellingExact(name);
		}
		finally {
			this.exactCase = savedExactCase;
		}
	}

	private String internSpellingExact(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		if (qn != null) {
			String pkg = registeredPackageName(this.registry.canonicalName(qn.pkg()));
			if (this.registry.contains(pkg)) {
				try {
					if (resolveQualified(qn) instanceof LispSymbol sym) {
						return sym.name();
					}
				}
				catch (LispPackageException ignored) {
					// A single-colon spelling of a symbol the package does not export:
					// intern is not the reader, so it interns rather than refusing.
				}
				if (LispNames.CL_PKG.equals(pkg) || LispNames.CL_USER_PKG.equals(pkg)) {
					return qn.member();
				}
				return canonical(pkg, qn.member()).name();
			}
		}
		if (("T".equals(name) || "NIL".equals(name)) && currentUsesCl()) {
			// The reader never mints PKG::T -- t and nil read as the singletons in every
			// cl-using package -- so intern answers them too, bare, rather than the
			// qualified spelling the resolver would give a name cl owns (CL:T under cl).
			LispPackage current = this.registry.get(this.currentPackage);
			if (current == null || (!current.shadows(name) && !current.imports().containsKey(name))) {
				return name;
			}
		}
		try {
			if (resolveUnqualified(name) instanceof LispSymbol sym) {
				return sym.name();
			}
		}
		catch (LispPackageException ignored) {
			// An inaccessible cl symbol in a non-cl-using package: intern it into the
			// current package like CL would.
		}
		return canonical(this.currentPackage, name).name();
	}

	/**
	 * The member-table write of the runtime {@code intern}: the home package of the
	 * spelling {@code intern} answered now OWNS the member name, unless it already
	 * provides it (an own or imported symbol; a name {@code unintern} took out is homed
	 * again). The package's own symbols are the member table (see {@link LispPackage}),
	 * which is what lets {@code find-symbol} answer nil before an intern and the symbol
	 * after, and {@code do-symbols} enumerate what a program interned. Only the runtime
	 * {@code intern} writes it: a symbol merely READ under a package is not recorded,
	 * deliberately -- recording every name the resolver mints would grow every package's
	 * enumeration (and the compiled backends' baked tables) with every local variable
	 * ever read, and the ANSI tests that would notice are the ones the driver cannot run
	 * anyway (they need {@code in-package}).
	 * @param spelling the canonical spelling {@code intern} answered
	 */
	private void recordInterned(String spelling) {
		if (spelling.startsWith(":") || spelling.startsWith("#:")) {
			return;
		}
		String home = homeOf(spelling);
		if (home == null) {
			return;
		}
		String pkg = registeredPackageName(this.registry.canonicalName(home));
		if (!this.registry.contains(pkg)) {
			return;
		}
		String member = LispSymbol.memberName(spelling);
		LispPackage p = this.registry.get(pkg);
		if (p.imports().containsKey(member)) {
			return;
		}
		if (p.owns(member) && !this.registry.isUnhomed(pkg, member)) {
			return;
		}
		p.addSymbol(member);
		this.registry.rehome(pkg, member);
		this.registry.markRecorded(pkg, member);
	}

	/**
	 * The symbol a package makes accessible under a name, with its accessibility status
	 * -- the single lookup behind {@code find-symbol}, its status value, the
	 * {@code do-symbols} enumeration and the package operators' conflict checks, so they
	 * cannot disagree.
	 *
	 * @param spelling the symbol's canonical spelling
	 * @param status {@code :internal}, {@code :external} or {@code :inherited} (the
	 * {@code LispNames.STATUS_*} spellings)
	 */
	public record Accessible(String spelling, String status) {
	}

	/**
	 * The symbol a designated package makes accessible under a name, or null when the
	 * package does not provide it. In order: the {@code keyword} pseudo-package builds
	 * the keyword; {@code cl} answers every standard name (the exported-only ones
	 * included, CLHS 11.1.2.1) plus whatever was interned into it; any other package
	 * answers a PRESENT symbol first -- an import redirect (spelled at its home), an own
	 * symbol (the member table, or a name {@code definedProbe} finds in the image: a
	 * definition IS an interning) -- and then an INHERITED one, the first used package in
	 * use order that exports the name, spelled at its home. A name {@code unintern} took
	 * out of the package is not its own any more, so only the inherited arm can answer
	 * it.
	 * @param pkgDesignator the package name as given (any case, nickname allowed)
	 * @param member the verbatim symbol name
	 * @param definedProbe whether a canonical spelling names a definition in the image
	 * (the interpreter's global namespaces; a constant false when there is no image)
	 * @return the accessible symbol and its status, or null
	 * @throws LispPackageException when no such package exists
	 */
	public @Nullable Accessible accessible(String pkgDesignator, String member, Predicate<String> definedProbe) {
		String pkg = findPackageName(pkgDesignator);
		if (pkg == null) {
			throw new LispPackageException("No such package: " + pkgDesignator);
		}
		return accessibleIn(pkg, member, definedProbe);
	}

	private @Nullable Accessible accessibleIn(String pkg, String member, Predicate<String> definedProbe) {
		if ("keyword".equals(pkg)) {
			return new Accessible(":" + member, LispNames.STATUS_EXTERNAL);
		}
		LispPackage p;
		try {
			p = this.registry.get(pkg);
		}
		catch (LispPackageException ignored) {
			return null;
		}
		boolean unhomed = this.registry.isUnhomed(pkg, member);
		if (LispNames.CL_PKG.equals(pkg)) {
			if (PackageRegistry.isClMemberName(member)) {
				return new Accessible(member, clSymbolStatus(member, false));
			}
			return !unhomed && p.owns(member) ? new Accessible(canonical(pkg, member).name(), LispNames.STATUS_INTERNAL)
					: null;
		}
		Accessible present = presentIn(pkg, member);
		if (present != null) {
			return present;
		}
		if (!unhomed) {
			if (LispNames.CL_USER_PKG.equals(pkg) && PackageRegistry.isClMemberName(member)) {
				// A standard name reaches cl-user through its use of cl -- inherited,
				// except for cl's %-prefixed helpers, which are internal wherever
				// they are reached from.
				return new Accessible(member, clSymbolStatus(member, true));
			}
			String defined = LispNames.CL_USER_PKG.equals(pkg) ? member : PackageRegistry.qualifyInternal(pkg, member);
			if (definedProbe.test(defined)) {
				return new Accessible(defined, LispNames.STATUS_INTERNAL);
			}
		}
		for (String used : p.useList()) {
			Accessible inherited = inheritedFrom(used, member);
			if (inherited != null) {
				return inherited;
			}
		}
		return null;
	}

	/**
	 * The symbol PRESENT in a package under a name -- an import redirect or an own member
	 * (the table, minus what {@code unintern} took out) -- or null. Inherited symbols and
	 * image definitions are not present; this is the conflict checks' view.
	 */
	private @Nullable Accessible presentIn(String pkg, String member) {
		LispPackage p;
		try {
			p = this.registry.get(pkg);
		}
		catch (LispPackageException ignored) {
			return null;
		}
		String status = p.exports(member) ? LispNames.STATUS_EXTERNAL : LispNames.STATUS_INTERNAL;
		String importHome = p.imports().get(member);
		if (importHome != null) {
			return new Accessible(homeSpelling(importHome, member), status);
		}
		if (p.owns(member) && !this.registry.isUnhomed(pkg, member)) {
			return new Accessible(ownSpelling(pkg, member), status);
		}
		return null;
	}

	/**
	 * The symbol a package EXPORTS under a name, as the packages using it inherit it, or
	 * null: for {@code cl} the standard externals (bare), for any other package its
	 * external set, a re-export redirected to its home ({@link #usedExport}). A used
	 * package deleted since contributes nothing.
	 */
	private @Nullable Accessible inheritedFrom(String used, String member) {
		LispPackage source;
		try {
			source = this.registry.get(used);
		}
		catch (LispPackageException ignored) {
			return null;
		}
		if (LispNames.CL_PKG.equals(used)) {
			return source.exports(member) && PackageRegistry.isClMemberName(member)
					? new Accessible(member, LispNames.STATUS_INHERITED) : null;
		}
		LispSymbol viaUsed = usedExport(used, member);
		return viaUsed == null ? null : new Accessible(viaUsed.name(), LispNames.STATUS_INHERITED);
	}

	/** The spelling of a package's OWN member: bare in cl-user, qualified elsewhere. */
	private String ownSpelling(String pkg, String member) {
		return LispNames.CL_USER_PKG.equals(pkg) ? member : canonical(pkg, member).name();
	}

	/**
	 * The spelling of a symbol homed in {@code home}: bare for a {@code cl} / {@code
	 * cl-user} home, a keyword for the keyword package, qualified otherwise.
	 */
	private String homeSpelling(String home, String member) {
		if ("keyword".equals(home)) {
			return ":" + member;
		}
		if (LispNames.CL_PKG.equals(home) || LispNames.CL_USER_PKG.equals(home)) {
			return member;
		}
		return canonical(home, member).name();
	}

	/**
	 * Every symbol accessible in a designated package with its status, in spelling order:
	 * the present ones (imports at their home spelling, then the own members the table
	 * holds), then the ones inherited through the use list -- a used package's externals,
	 * each spelled at its home, skipping any name the package already has present (a
	 * shadowing symbol, or the same symbol reached twice). The {@code do-symbols} /
	 * {@code with-package-iterator} universe; every entry is what {@link #accessible}
	 * answers for its name, so the two cannot disagree.
	 * @param packageDesignator the package name as given (any case, nickname allowed)
	 * @return the accessible symbols with their statuses
	 * @throws LispPackageException when no such package exists
	 */
	public List<Accessible> accessibleEntries(String packageDesignator) {
		String pkg = findPackageName(packageDesignator);
		if (pkg == null) {
			throw new LispPackageException("No such package: " + packageDesignator);
		}
		LispPackage p;
		try {
			p = this.registry.get(pkg);
		}
		catch (LispPackageException ignored) {
			// Findable but unregistered: the keyword pseudo-package (its "symbols"
			// are the keywords, enumerated nowhere).
			return List.of();
		}
		Map<String, Accessible> bySpelling = new java.util.TreeMap<>();
		Set<String> presentNames = new HashSet<>();
		if (LispNames.CL_PKG.equals(pkg)) {
			for (String name : p.symbols()) {
				if (this.registry.isUnhomed(pkg, name)) {
					continue;
				}
				String spelling = PackageRegistry.isClMemberName(name) ? name : canonical(pkg, name).name();
				bySpelling.put(spelling, new Accessible(spelling,
						p.exports(name) ? LispNames.STATUS_EXTERNAL : LispNames.STATUS_INTERNAL));
			}
			return new ArrayList<>(bySpelling.values());
		}
		for (String name : p.imports().keySet()) {
			Accessible present = presentIn(pkg, name);
			if (present != null) {
				bySpelling.put(present.spelling(), present);
				presentNames.add(name);
			}
		}
		for (String name : p.symbols()) {
			if (presentNames.contains(name)) {
				continue;
			}
			Accessible present = presentIn(pkg, name);
			if (present != null) {
				bySpelling.put(present.spelling(), present);
				presentNames.add(name);
			}
		}
		for (String used : p.useList()) {
			LispPackage source;
			try {
				source = this.registry.get(used);
			}
			catch (LispPackageException ignored) {
				// A use entry left stale by a delete-package: contributes nothing.
				continue;
			}
			for (String name : source.externals()) {
				if (presentNames.contains(name)) {
					continue;
				}
				Accessible inherited = inheritedFrom(used, name);
				if (inherited != null) {
					bySpelling.putIfAbsent(inherited.spelling(), inherited);
				}
			}
		}
		return new ArrayList<>(bySpelling.values());
	}

	/**
	 * The external symbols of a designated package, as canonically spelled symbols in a
	 * stable (sorted) order -- the {@code do-external-symbols} iteration source. A
	 * re-exported symbol (an import redirect, or an {@code :export} of an inherited name)
	 * is spelled at its HOME, as {@code find-symbol} answers it.
	 * @param packageDesignator the package name as given (any case, nickname allowed)
	 * @return the external symbols, canonically spelled
	 * @throws LispPackageException when no such package exists
	 */
	public java.util.List<LispSymbol> externalSymbols(String packageDesignator) {
		java.util.List<LispSymbol> out = new java.util.ArrayList<>();
		for (Accessible entry : accessibleEntries(packageDesignator)) {
			if (LispNames.STATUS_EXTERNAL.equals(entry.status())) {
				out.add(new LispSymbol(entry.spelling()));
			}
		}
		return out;
	}

	/**
	 * The symbols ACCESSIBLE in a designated package -- the ones it owns (external and
	 * internal alike), the ones it imports, and the external symbols of every package it
	 * uses -- as canonically spelled symbols in a stable (sorted) order, the
	 * {@code do-symbols} iteration source.
	 *
	 * <p>
	 * Every symbol is spelled the way code spells it -- at its HOME: a
	 * {@code (do-symbols (s :my-pkg))} over a package that uses {@code cl} yields
	 * {@code CAR}, not {@code MY-PKG::CAR} and not {@code cl:CAR}; a shadowing import
	 * yields the imported package's symbol. That is what makes an enumerated symbol
	 * {@code eq} to what {@code find-symbol} answers for its name. A name accessible
	 * along two routes is listed once (see {@link #accessibleEntries}).
	 * @param packageDesignator the package name as given (any case, nickname allowed)
	 * @return the accessible symbols, canonically spelled
	 * @throws LispPackageException when no such package exists
	 */
	public java.util.List<LispSymbol> accessibleSymbols(String packageDesignator) {
		java.util.List<LispSymbol> out = new java.util.ArrayList<>();
		for (Accessible entry : accessibleEntries(packageDesignator)) {
			out.add(new LispSymbol(entry.spelling()));
		}
		return out;
	}

	/**
	 * {@link #internSpelling(String)} against an EXPLICIT package instead of the current
	 * one, backing the two-argument {@code (intern name package)}. The {@code keyword}
	 * pseudo-package answers with the {@code :}-prefixed spelling directly, since it has
	 * no registration to intern into.
	 * @param packageDesignator the package to intern into
	 * @param name the bare name to intern
	 * @return the canonical spelling for that package
	 * @throws LispPackageException if no such package exists
	 */
	public String internSpellingIn(String packageDesignator, String name) {
		return internSpellingIn(packageDesignator, name, false);
	}

	/**
	 * As {@link #internSpellingIn(String, String)}, recording a freshly minted name in
	 * the designated package when {@code record} is true (see
	 * {@link #internSpelling(String, boolean)}).
	 * @param packageDesignator the package to intern into
	 * @param name the bare name to intern
	 * @param record whether to record a freshly minted name in its package
	 * @return the canonical spelling for that package
	 * @throws LispPackageException if no such package exists
	 */
	public String internSpellingIn(String packageDesignator, String name, boolean record) {
		String pkg = findPackageName(packageDesignator);
		if (pkg == null) {
			throw new LispPackageException("No such package: " + packageDesignator);
		}
		if ("keyword".equals(pkg)) {
			return ":" + name;
		}
		String saved = this.currentPackage;
		this.currentPackage = pkg;
		try {
			return internSpelling(name, record);
		}
		finally {
			this.currentPackage = saved;
		}
	}

	/**
	 * The canonical registered name of a runtime package designator, or null when no such
	 * package exists. The {@code keyword} pseudo-package answers as {@code "keyword"}
	 * even though it is not a registration (its "symbols" are the keywords). This backs
	 * the runtime {@code find-package}: rontolisp has no package objects, so a "package"
	 * at runtime is its canonical name (as a keyword) and {@code eq} compares those by
	 * name.
	 * @param designator the package name as given (any case, nickname allowed)
	 * @return the canonical registered name, or null
	 */
	public @Nullable String findPackageName(String designator) {
		if (designator.isEmpty()) {
			return null;
		}
		if ("KEYWORD".equalsIgnoreCase(designator)) {
			return "keyword";
		}
		String canonical = registeredPackageName(
				this.registry.canonicalName(PackageRegistry.canonicalBuiltinName(designator)));
		if (this.registry.contains(canonical)) {
			return this.registry.canonicalName(canonical);
		}
		String lower = designator.toLowerCase(java.util.Locale.ROOT);
		if (!lower.equals(designator)) {
			return findPackageName(lower);
		}
		return null;
	}

	/**
	 * The table a compiled backend bakes in so a COMPUTED {@code (find-package x)} can be
	 * answered without a registry: every designator that names a package (canonical names
	 * and nicknames, upcased) mapped to the upcased canonical name the "package value"
	 * keyword is built from. Read AFTER {@link #resolveProgram}, so it covers every
	 * {@code defpackage} in the program. The interpreter does not use it -- it keeps the
	 * live registry and therefore also sees packages created after compilation would have
	 * frozen this snapshot.
	 * @return the designator-to-package-name table, in a deterministic order
	 */
	public Map<String, String> runtimePackageTable() {
		Map<String, String> table = new java.util.TreeMap<>();
		Map<String, String> designators = new java.util.HashMap<>(this.registry.designatorTable());
		// The keyword pseudo-package is not a registration (its "symbols" are the
		// keywords), so findPackageName special-cases it and so must this table.
		designators.put("keyword", "keyword");
		designators.forEach((designator, canonical) -> {
			// findPackageName matches a designator against the registered spelling either
			// verbatim or after lowercasing it -- never the other way round, which is why
			// a built-in (registered lowercase) answers to both cases while a user
			// defpackage (registered as the reader upcased it) answers only to its own.
			// Both accepted spellings go in so the lookup can stay a verbatim match.
			String value = canonical.toUpperCase(java.util.Locale.ROOT);
			table.put(designator, value);
			table.put(designator.toUpperCase(java.util.Locale.ROOT), value);
		});
		return table;
	}

	/**
	 * The table a compiled backend bakes in so {@code list-all-packages},
	 * {@code package-use-list} and {@code package-used-by-list} can be answered without a
	 * registry: every registered package's UPCASED canonical name mapped to the upcased
	 * canonical names of the packages it uses. Read AFTER {@link #resolveProgram}, so it
	 * covers every {@code defpackage} in the program; the interpreter does not use it --
	 * it keeps the live registry. The {@code keyword} pseudo-package is a member here for
	 * the same reason it is in {@link #runtimePackageTable}: {@code find-package} answers
	 * for it, so the listing must contain it.
	 * @return the package-to-use-list table, in a deterministic order
	 */
	public Map<String, List<String>> runtimePackageUseTable() {
		Map<String, List<String>> table = new java.util.TreeMap<>();
		for (String canonical : new java.util.TreeSet<>(this.registry.designatorTable().values())) {
			LispPackage pkg = this.registry.get(canonical);
			List<String> used = new ArrayList<>();
			if (pkg != null) {
				for (String use : pkg.useList()) {
					used.add(use.toUpperCase(java.util.Locale.ROOT));
				}
			}
			table.put(canonical.toUpperCase(java.util.Locale.ROOT), List.copyOf(used));
		}
		table.putIfAbsent("KEYWORD", List.of());
		return table;
	}

	/**
	 * One entry of {@link #runtimeBakedPackages}: everything a compiled program needs to
	 * answer the runtime package API without a registry.
	 *
	 * @param name the upcased canonical package name
	 * @param use the upcased canonical names of the used packages
	 * @param nicknames the nickname strings
	 * @param accessible the canonically spelled accessible symbols (the
	 * {@code do-symbols} universe of this package)
	 * @param externals the canonically spelled external symbols (the
	 * {@code do-external-symbols} universe)
	 * @param imports the recorded import redirects as {@code (member, home)} pairs
	 * (member verbatim, home upcased) -- what spells an enumerated re-export at its true
	 * home
	 * @param shadows the canonically spelled shadowing symbols (the
	 * {@code package-shadowing-symbols} answer)
	 */
	public record BakedPackage(String name, java.util.List<String> use, java.util.List<String> nicknames,
			java.util.List<LispSymbol> accessible, java.util.List<LispSymbol> externals,
			java.util.List<java.util.List<String>> imports, java.util.List<LispSymbol> shadows) {
	}

	/**
	 * The table a compiled backend injects as {@code %baked-packages%} when the program
	 * can create, enumerate or nickname packages at run time: one entry per registered
	 * package (plus the {@code keyword} pseudo-package), in a deterministic order. Read
	 * AFTER {@link #resolveProgram}, like {@link #runtimePackageUseTable}. The
	 * interpreter does not use it -- it keeps the live registry.
	 * @return the baked package entries
	 */
	public java.util.List<BakedPackage> runtimeBakedPackages() {
		java.util.List<BakedPackage> out = new java.util.ArrayList<>();
		for (String canonical : new java.util.TreeSet<>(this.registry.designatorTable().values())) {
			String upcased = canonical.toUpperCase(java.util.Locale.ROOT);
			java.util.List<String> used = new java.util.ArrayList<>();
			LispPackage pkg = null;
			try {
				pkg = this.registry.get(canonical);
			}
			catch (LispPackageException ignored) {
				// A nickname-only designator value cannot happen (values are
				// canonical names), but stay total: the entry keeps empty lists.
			}
			if (pkg != null) {
				for (String use : pkg.useList()) {
					used.add(use.toUpperCase(java.util.Locale.ROOT));
				}
			}
			java.util.List<LispSymbol> accessible;
			java.util.List<LispSymbol> externals;
			java.util.List<LispSymbol> shadows = new java.util.ArrayList<>();
			try {
				accessible = accessibleSymbols(canonical);
				externals = externalSymbols(canonical);
				for (String spelling : shadowingSymbols(canonical)) {
					shadows.add(new LispSymbol(spelling));
				}
			}
			catch (LispPackageException ignored) {
				accessible = java.util.List.of();
				externals = java.util.List.of();
			}
			java.util.List<String> nicknames = pkg == null ? java.util.List.of()
					: this.registry.nicknamesFor(canonical);
			java.util.List<java.util.List<String>> imports = new java.util.ArrayList<>();
			if (pkg != null) {
				new java.util.TreeMap<>(pkg.imports()).forEach((member, home) -> imports
					.add(java.util.List.of(member, home.toUpperCase(java.util.Locale.ROOT))));
			}
			out.add(new BakedPackage(upcased, java.util.List.copyOf(used), nicknames, accessible, externals,
					java.util.List.copyOf(imports), java.util.List.copyOf(shadows)));
		}
		if (out.stream().noneMatch(entry -> "KEYWORD".equals(entry.name()))) {
			out.add(new BakedPackage("KEYWORD", java.util.List.of(), java.util.List.of(), java.util.List.of(),
					java.util.List.of(), java.util.List.of(), java.util.List.of()));
		}
		return out;
	}

	/**
	 * The canonical name of the package a symbol lives in: the qualifier of a qualified
	 * spelling, {@code "keyword"} for a keyword, {@code cl} for a standard symbol,
	 * {@code cl-user} otherwise; null for an uninterned ({@code #:}) symbol. The runtime
	 * {@code symbol-package} answer, kept consistent with {@link #findPackageName} so the
	 * two are {@code eq}-comparable (ironclad's {@code massage-symbol} pattern).
	 * @param symbolName the stored symbol name
	 * @return the canonical package name, or null
	 */
	public @Nullable String symbolPackageName(String symbolName) {
		String home = homeOf(symbolName);
		if (home == null || "keyword".equals(home)) {
			return home;
		}
		// A symbol unintern took out of its home package has none (CL's symbol-package
		// nil), until an intern of the name homes it again.
		String registered = registeredPackageName(this.registry.canonicalName(home));
		if (this.registry.isUnhomed(registered, LispSymbol.memberName(symbolName))) {
			return null;
		}
		return home;
	}

	/**
	 * The canonical spelling {@code (find-symbol name pkg)} yields, or null when the
	 * package does not provide the name -- {@link #accessible} without an image probe.
	 * @param pkgDesignator the package name as given
	 * @param member the verbatim symbol name
	 * @return the canonical spelling, or null
	 */
	public @Nullable String memberSpelling(String pkgDesignator, String member) {
		Accessible found = accessible(pkgDesignator, member, s -> false);
		return found == null ? null : found.spelling();
	}

	/**
	 * The accessibility status {@code (find-symbol name pkg)} answers as its SECOND
	 * value: {@code :external}, {@code :internal}, {@code :inherited}, or null when the
	 * package does not provide the name -- the status half of {@link #accessible}, so the
	 * pair is null together (CL's own invariant).
	 * @param pkgDesignator the package name as given
	 * @param member the verbatim symbol name
	 * @return the status keyword spelling (with its leading colon), or null
	 */
	public @Nullable String memberStatus(String pkgDesignator, String member) {
		Accessible found = accessible(pkgDesignator, member, s -> false);
		return found == null ? null : found.status();
	}

	/**
	 * The status of a {@code cl} symbol read through {@code cl} itself or through a user.
	 */
	private static String clSymbolStatus(String member, boolean throughUseList) {
		// The %-prefixed helpers are owned by cl but not exported, so they are internal
		// wherever they are reached from.
		if (member.startsWith("%")) {
			return LispNames.STATUS_INTERNAL;
		}
		return throughUseList ? LispNames.STATUS_INHERITED : LispNames.STATUS_EXTERNAL;
	}

	private static String operatorMember(LispSymbol op) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
		return qn == null ? op.name() : qn.member();
	}

	private static LispVal quotedSymbol(String name) {
		return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(new LispSymbol(name), LispNil.INSTANCE));
	}

	/**
	 * Whether the symbol spelled {@code symbolName} prints WITHOUT its package qualifier
	 * in the current package -- CLHS 22.1.3.3.1: no qualifier when the symbol is
	 * accessible in {@code *package*} (its own, inherited through {@code :use} as an
	 * external, or imported), {@code pkg:name} / {@code pkg::name} otherwise. Decided
	 * exactly as a reference is resolved: the symbol is accessible when an unqualified
	 * reference to its name in the current package resolves to it. The interpreter's
	 * printer asks this against the LIVE registry ({@code LispEvaluator},
	 * {@code %symbol-print-bare-p}); the compile paths bake the same answers into a
	 * {@link SymbolPrintTable}.
	 * @param symbolName the stored (canonical) symbol name
	 * @return {@code true} when the qualifier is dropped; also for a name that carries
	 * none (a bare, keyword or uninterned symbol prints as it is)
	 */
	public boolean printsBare(String symbolName) {
		if (symbolName.startsWith(":") || symbolName.startsWith("#:")) {
			return true;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		if (qn == null) {
			return true;
		}
		String home = this.registry.canonicalName(qn.pkg());
		if (home.equals(this.currentPackage)) {
			return true;
		}
		boolean savedQuoted = this.inQuotedData;
		this.inQuotedData = true;
		try {
			return resolveUnqualified(qn.member()) instanceof LispSymbol sym && sym.name().equals(symbolName);
		}
		catch (LispPackageException ex) {
			return false;
		}
		finally {
			this.inQuotedData = savedQuoted;
		}
	}

	/**
	 * Whether the current package is {@code cl-user} as the registry seeded it -- using
	 * only {@code cl}, importing nothing -- so that no qualified symbol can be accessible
	 * in it and the printer's raw conversion is already exact. The fast path of the
	 * printer-control renderer ({@code %print-package-raw-p}).
	 * @return {@code true} when the raw conversion needs no accessibility check
	 */
	public boolean currentPackageIsPristineClUser() {
		return LispNames.CL_USER_PKG.equals(this.currentPackage) && clUserIsPristine();
	}

	private boolean clUserIsPristine() {
		LispPackage clUser = this.registry.get(LispNames.CL_USER_PKG);
		return clUser != null && clUser.useList().equals(List.of(LispNames.CL_PKG)) && clUser.imports().isEmpty()
				&& clUser.shadows().isEmpty();
	}

	/**
	 * The {@link SymbolPrintTable} a compiled backend bakes in so its printer can drop
	 * the qualifier of an accessible symbol without a registry. Read AFTER
	 * {@link #resolveProgram}, over the RESOLVED program: the correction lists are
	 * computed for every package-qualified symbol that occurs in it, against every
	 * registered package, by asking {@link #printsBare} the same question the interpreter
	 * asks at print time -- so the two agree on every symbol the program spells, and a
	 * symbol interned at run time follows the structural rule alone.
	 * @param resolvedProgram the resolved top-level forms
	 * @return the table, in a deterministic order
	 */
	public SymbolPrintTable symbolPrintTable(List<LispVal> resolvedProgram) {
		java.util.SequencedMap<String, List<String>> rows = new java.util.LinkedHashMap<>();
		java.util.SequencedMap<String, List<String>> extra = new java.util.LinkedHashMap<>();
		java.util.SequencedMap<String, List<String>> excluded = new java.util.LinkedHashMap<>();
		java.util.Set<String> occurring = new java.util.LinkedHashSet<>();
		for (LispVal form : resolvedProgram) {
			collectQualifiedSymbols(form, occurring);
		}
		String savedPackage = this.currentPackage;
		try {
			for (String pkg : new java.util.TreeSet<>(this.registry.designatorTable().values())) {
				LispPackage registered = this.registry.get(pkg);
				if (registered == null) {
					continue;
				}
				List<String> row = new ArrayList<>();
				row.add(pkg);
				for (String used : registered.useList()) {
					if (!LispNames.CL_PKG.equals(used)) {
						row.add(used);
					}
				}
				String key = pkg.toUpperCase(java.util.Locale.ROOT);
				rows.put(key, List.copyOf(row));
				this.currentPackage = pkg;
				List<String> extraHere = new ArrayList<>();
				List<String> excludedHere = new ArrayList<>();
				for (String symbol : occurring) {
					PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbol);
					if (qn == null) {
						continue;
					}
					String home = this.registry.canonicalName(qn.pkg());
					boolean rule = home.equals(pkg) || (!qn.internal() && row.contains(home));
					boolean truth = printsBare(symbol);
					if (truth && !rule) {
						extraHere.add(symbol);
					}
					else if (rule && !truth) {
						excludedHere.add(symbol);
					}
				}
				if (!extraHere.isEmpty()) {
					extra.put(key, List.copyOf(extraHere));
				}
				if (!excludedHere.isEmpty()) {
					excluded.put(key, List.copyOf(excludedHere));
				}
			}
		}
		finally {
			this.currentPackage = savedPackage;
		}
		return new SymbolPrintTable(rows, extra, excluded, clUserIsPristine());
	}

	/**
	 * Every package-qualified symbol spelled anywhere in the form (quoted data included).
	 */
	private static void collectQualifiedSymbols(LispVal form, java.util.Set<String> into) {
		if (form instanceof LispSymbol sym) {
			if (!sym.isKeyword() && !sym.name().startsWith("#:")
					&& PackageRegistry.splitQualified(sym.name()) != null) {
				into.add(sym.name());
			}
			return;
		}
		for (LispVal cur = form; cur instanceof LispCons cell; cur = cell.cdr()) {
			collectQualifiedSymbols(cell.car(), into);
			if (!(cell.cdr() instanceof LispCons) && !(cell.cdr() instanceof LispNil)) {
				collectQualifiedSymbols(cell.cdr(), into);
			}
		}
	}

}
