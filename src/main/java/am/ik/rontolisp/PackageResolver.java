package am.ik.rontolisp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <li>{@code *package*} is replaced by a quoted symbol naming the current package;</li>
 * <li>{@code (in-package P)} is consumed and replaced by a quoted package symbol;</li>
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
	 * package's name (see {@link #resolveUnqualified}).
	 */
	private boolean inQuotedData = false;

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
		List<LispVal> out = new ArrayList<>(program.size());
		for (LispVal form : program) {
			out.add(resolve(form));
		}
		return out;
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
			if (LispNames.PUSH_PACKAGE.equals(member)) {
				pushPackage();
				return quotedSymbol(this.currentPackage);
			}
			if (LispNames.POP_PACKAGE.equals(member)) {
				popPackage();
				return quotedSymbol(this.currentPackage);
			}
			// The ASDF provenance brackets are consumed here too, so a marker never
			// survives into a backend as a call to an undefined %END-SYSTEM in whatever
			// package the spliced file selected. Unlike the package markers they carry no
			// state: the pruner reads them from the UNRESOLVED program (which is
			// index-aligned with the resolved copy) and drops them from its output.
			if (LispNames.BEGIN_SYSTEM.equals(member) || LispNames.END_SYSTEM.equals(member)) {
				return quotedSymbol(this.currentPackage);
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
		}
		return resolveForm(form);
	}

	private boolean isUiopOperator(LispSymbol op) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
		return qn != null && LispNames.UIOP_PKG.equals(this.registry.canonicalName(qn.pkg()));
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
	 * Consumes a literal top-level {@code (use-package PACKAGES [PACKAGE])} call: widens
	 * the use list of the target package (the current one by default) and returns
	 * {@code t}, the standard function's return value. Returns null when an argument is
	 * not a literal designator -- a runtime call only the interpreter can serve.
	 */
	private @Nullable LispVal tryConsumeUsePackage(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2 || parts.size() > 3) {
			throw new LispPackageException(
					LispNames.USE_PACKAGE + " expects a package designator (and optionally a target package)");
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
		List<String> names = literalDesignatorList(parts.get(1));
		String target = this.currentPackage;
		if (parts.size() == 3) {
			target = literalDesignator(parts.get(2));
		}
		if (names == null || target == null) {
			return null;
		}
		exportSymbols(names, target, export);
		return LispTrue.INSTANCE;
	}

	/**
	 * Makes the named symbols external (or, with {@code export} false, internal again) in
	 * the target package -- the shared machinery behind the
	 * {@code export}/{@code unexport} directives and their interpreter-side runtime
	 * functions. A name the package does not define but INHERITS through its use list is
	 * re-exported through the same import redirect {@code defpackage}'s {@code :export}
	 * clause records, so the exported symbol stays the used package's one rather than a
	 * fresh symbol of the same name.
	 * @param names the symbol names to export (a qualified spelling is reduced to its
	 * member name)
	 * @param targetPackage the package whose external set changes
	 * @param export true to export, false to unexport
	 */
	public void exportSymbols(List<String> names, String targetPackage, boolean export) {
		String target = registeredPackageName(this.registry.canonicalName(targetPackage));
		if (!this.registry.contains(target)) {
			throw new LispPackageException("No such package: " + targetPackage);
		}
		LispPackage pkg = this.registry.get(target);
		Set<String> externals = new HashSet<>(pkg.externals());
		Set<String> owned = new HashSet<>(pkg.symbols());
		Map<String, String> imports = new HashMap<>(pkg.imports());
		for (String spelled : names) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(spelled);
			String name = qn == null ? spelled : qn.member();
			if (!export) {
				// unexport leaves the symbol PRESENT, just no longer external.
				externals.remove(name);
				continue;
			}
			externals.add(name);
			owned.add(name);
			if (!pkg.shadows().contains(name) && !imports.containsKey(name) && !PackageRegistry.isClSymbol(name)) {
				for (String used : pkg.useList()) {
					if (!LispNames.CL_PKG.equals(used) && this.registry.get(used).exports(name)) {
						imports.put(name, trueHome(used, name));
						break;
					}
				}
			}
		}
		this.registry.define(new LispPackage(pkg.name(), pkg.useList(), Set.copyOf(owned), Set.copyOf(externals),
				Map.copyOf(imports), pkg.shadows()));
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
		return quotedSymbol(name);
	}

	/**
	 * Defines a new package from a literal, top-level {@code (defpackage NAME
	 * (:use ...) (:export ...))} directive. Exported names become owned and external;
	 * symbols interned later (defuns under {@code (in-package NAME)}, free variables) are
	 * internal. Without a {@code :use} clause nothing is visible unqualified (like SBCL),
	 * so {@code (:use :cl)} must be spelled out. {@code :nicknames} registers alternate
	 * package names, {@code :import-from} maps the named symbols to their source package
	 * (resolution is textual, so an imported name simply resolves to the source package's
	 * canonical spelling), and {@code :documentation}/{@code :size} are accepted and
	 * ignored. {@code :shadow} records the named symbols so their unqualified uses inside
	 * the package resolve package-locally (see {@link #resolveUnqualified}).
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
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new LispPackageException(LispNames.DEFPACKAGE + " expects a package name");
		}
		String name = designator(LispNames.DEFPACKAGE, "a package name", parts.get(1));
		if (this.registry.contains(name)) {
			throw new LispPackageException("Package already exists: " + name);
		}
		List<String> useList = new ArrayList<>();
		Set<String> exports = new HashSet<>();
		List<String> nicknames = new ArrayList<>();
		Map<String, String> imports = new HashMap<>();
		Set<String> shadows = new HashSet<>();
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
							throw new LispPackageException("No such package: " + used);
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
				case LispNames.NICKNAMES_KEYWORD -> {
					for (LispVal arg : args.subList(1, args.size())) {
						String nickname = designator(LispNames.NICKNAMES_KEYWORD, "a package name", arg);
						if (this.registry.contains(nickname)) {
							throw new LispPackageException("Package already exists: " + nickname);
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
				case LispNames.IMPORT_FROM_KEYWORD -> {
					if (args.size() < 2) {
						throw new LispPackageException(LispNames.IMPORT_FROM_KEYWORD + " expects a package name");
					}
					String source = registeredPackageName(this.registry
						.canonicalName(designator(LispNames.IMPORT_FROM_KEYWORD, "a package name", args.get(1))));
					if (!this.registry.contains(source)) {
						throw new LispPackageException("No such package: " + source);
					}
					for (LispVal arg : args.subList(2, args.size())) {
						String member = designator(LispNames.IMPORT_FROM_KEYWORD, "a symbol name", arg);
						// The upcase reader premise upcases the designator while a
						// built-in source package's canonical spellings are lowercase:
						// fold when the lowercase spelling is the one the source
						// actually provides ((:import-from #:cl #:car) imports car).
						String lower = member.toLowerCase(java.util.Locale.ROOT);
						if (!member.equals(lower) && sourceProvides(source, lower) && !sourceProvides(source, member)) {
							member = lower;
						}
						imports.put(member, trueHome(source, member));
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
				case ":SHADOWING-IMPORT-FROM" -> throw new LispPackageException(LispNames.DEFPACKAGE + " "
						+ keyword.name() + " is not supported (rontolisp has no symbol shadowing import)");
				default -> throw new LispPackageException(
						"Unsupported " + LispNames.DEFPACKAGE + " clause: " + keyword.name());
			}
		}
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
		Set<String> owned = new HashSet<>(exports);
		owned.addAll(shadows);
		this.registry.define(new LispPackage(name, List.copyOf(useList), Set.copyOf(owned), Set.copyOf(exports),
				Map.copyOf(imports), Set.copyOf(shadows)));
		for (String nickname : nicknames) {
			this.registry.defineNickname(nickname, name);
		}
		return quotedSymbol(name);
	}

	private static String packageDesignator(String context, LispVal designator) {
		return designator(context, "a package name", designator);
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

	private static String designator(String context, String kind, LispVal designator) {
		return switch (designator) {
			// A keyword (:cl-user), an uninterned symbol (#:cl-user, the common
			// defpackage idiom) or a bare symbol (cl-user); strip the prefix.
			case LispSymbol sym -> sym.name().startsWith("#:") ? sym.name().substring(2)
					: sym.isKeyword() ? sym.name().substring(1) : sym.name();
			case LispString str -> str.value();
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
			return properListOf(resolved);
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
			return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(datum, LispNil.INSTANCE));
		}
		if (cons.car() instanceof LispSymbol rawOp && LispNames.DEFPACKAGE.equals(operatorMember(rawOp))) {
			// resolveCons only sees non-top-level forms (resolve() consumes the
			// top-level directive), so a defpackage here is out of place.
			throw new LispPackageException(LispNames.DEFPACKAGE + " is only supported as a literal top-level form");
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
				return new LispCons(resolveForm(cons.car()), resolveForm(cons.cdr()));
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
			return new LispCons(resolveForm(cons.car()), new LispCons(defs, resolveForm(defsCell.cdr())));
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
					resolvedParts.add(properListOf(newClause));
				}
				else {
					resolvedParts.add(resolveForm(clauseCell.car()));
				}
				clauses = clauseCell.cdr();
			}
			return properListOf(resolvedParts);
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
				return found == null ? LispNil.INSTANCE : quotedSymbol(":" + found.toUpperCase(java.util.Locale.ROOT));
			}
		}
		LispVal car = resolveForm(cons.car());
		if (car instanceof LispSymbol op) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
			if (qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg())) {
				if (isIntrospectionMember(qn.member())) {
					return resolveIntrospection(op, qn.member(), cons);
				}
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
					return new LispCons(op, cons.cdr());
				}
			}
		}
		// Walk the argument tail ELEMENT-WISE, never re-reading a tail as a form of its
		// own: a variable named `quote` followed by exactly one argument -- s-sql's
		// :copy op binds a `quote` group, so its body has (when quote (sql-expand
		// ...)) -- would otherwise make the tail look like (quote DATUM) and leave the
		// following argument unresolved.
		List<LispVal> rest = new ArrayList<>();
		LispVal tail = cons.cdr();
		while (tail instanceof LispCons tailCons) {
			rest.add(resolveForm(tailCons.car()));
			tail = tailCons.cdr();
		}
		LispVal result = tail instanceof LispNil ? LispNil.INSTANCE : resolveForm(tail);
		for (int i = rest.size() - 1; i >= 0; i--) {
			result = new LispCons(rest.get(i), result);
		}
		return new LispCons(car, result);
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
					resolvedName = new LispCons(new LispSymbol(LispNames.QUOTE),
							new LispCons(resolveSymbol(nameSym), LispNil.INSTANCE));
				}
				else {
					resolvedName = resolveForm(nameArg);
				}
				this.inHostFacingData = true;
				return new LispCons(op, new LispCons(resolvedName, resolveForm(nameCell.cdr())));
			}
			this.inHostFacingData = true;
			return new LispCons(op, resolveForm(cons.cdr()));
		}
		finally {
			this.inHostFacingData = saved;
		}
	}

	private static boolean isIntrospectionMember(String member) {
		return LispNames.LIST_FUNCTIONS.equals(member) || LispNames.LIST_MACROS.equals(member)
				|| LispNames.LIST_SPECIAL_FORMS.equals(member);
	}

	/**
	 * Normalizes an introspection call ({@code rontolisp:list-functions} and friends) so
	 * the backends only ever see zero arguments or one canonical keyword literal: the
	 * package-designator literal (keyword, bare symbol, quoted symbol or string) is
	 * validated against the registry and rewritten to a keyword.
	 */
	private LispVal resolveIntrospection(LispSymbol op, String member, LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() == 1) {
			return new LispCons(op, LispNil.INSTANCE);
		}
		if (parts.size() > 2) {
			throw new LispPackageException(member + " expects at most one package-designator argument");
		}
		LispVal designator = parts.get(1);
		if (designator instanceof LispCons quoted && quoted.car() instanceof LispSymbol quoteOp
				&& LispNames.QUOTE.equals(operatorMember(quoteOp)) && quoted.cdr() instanceof LispCons datumCell) {
			designator = datumCell.car();
		}
		String name = packageDesignator(member, designator);
		if (!this.registry.contains(name)) {
			throw new LispPackageException("No such package: " + name);
		}
		return new LispCons(op, new LispCons(new LispSymbol(":" + name), LispNil.INSTANCE));
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
			case LispCons c -> new LispCons(resolveQuotedDatum(c.car()), resolveQuotedDatum(c.cdr()));
			default -> datum;
		};
	}

	private LispVal resolveSymbol(LispSymbol sym) {
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
		if (LispNames.CL_PKG.equals(pkg) && LispNames.PACKAGE_VAR.equals(member)) {
			return quotedSymbol(this.currentPackage);
		}
		// The reader upcases user spellings while a package's canonical members may be
		// lowercase (a wit-import package's defuns derive from the WIT's lower-kebab
		// names): retry the lowercase spelling before judging externality.
		String lower = member.toLowerCase(java.util.Locale.ROOT);
		if (!lower.equals(member) && !providesMember(pkg, member) && providesMember(pkg, lower)) {
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
		return this.registry.contains(pkg) && isExternal(pkg, member);
	}

	private LispVal resolveUnqualified(String name) {
		// *package* stands for the current package -- but only where it is READ as a
		// variable. Quoted, it is the ordinary symbol cl:*package*.
		if (LispNames.PACKAGE_VAR.equals(name) && !this.inQuotedData) {
			if (currentUsesCl()) {
				return quotedSymbol(this.currentPackage);
			}
			throw new LispPackageException(
					"Undefined symbol: " + name + " (use " + PackageRegistry.qualify(LispNames.CL_PKG, name) + ")");
		}
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
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		if (!lower.equals(name)) {
			if (current.owns(lower) || current.exports(lower)) {
				return canonical(this.currentPackage, lower);
			}
			for (String used : current.useList()) {
				if (!LispNames.CL_PKG.equals(used)) {
					LispSymbol viaUsed = usedExport(used, lower);
					if (viaUsed != null) {
						return viaUsed;
					}
				}
			}
		}
		// The mirror image: `name` itself already arrives lower-kebab (a wit-import
		// directive's OWN quoted binding name is built directly as `new
		// LispSymbol(member)` from the WIT label, bypassing the reader entirely --
		// unlike a call site's `gl:create-shader`, which the reader upcases before this
		// method ever sees it). When the surrounding package was declared with an
		// ordinary hand-written `defpackage` (gl.lisp exports CREATE-SHADER precisely
		// because ITS `:export` clause IS read through the normal upcasing reader), the
		// lower-kebab retry above never fires -- lower.equals(name) is already true --
		// so without this the binding would resolve to the internal, lowercase
		// GL::create-shader while every call site resolves to the external
		// GL:CREATE-SHADER: the function compiles under one name and is called under
		// another (undefined-function, silently downgraded to a WASM call-time-error
		// stub that traps at runtime instead of failing to compile).
		String upper = name.toUpperCase(java.util.Locale.ROOT);
		if (!upper.equals(name)) {
			if (current.owns(upper) || current.exports(upper)) {
				return canonical(this.currentPackage, upper);
			}
			for (String used : current.useList()) {
				if (!LispNames.CL_PKG.equals(used)) {
					LispSymbol viaUsed = usedExport(used, upper);
					if (viaUsed != null) {
						return viaUsed;
					}
				}
			}
		}
		// Unknown symbol: a user definition or forward reference in the current package.
		return canonical(this.currentPackage, name);
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
		return home == null ? source : home;
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
	 * internal one (so the canonical form re-resolves to itself).
	 */
	private LispSymbol canonical(String pkg, String name) {
		if (LispNames.CL_USER_PKG.equals(pkg)) {
			return new LispSymbol(name);
		}
		if (isExternal(pkg, name)) {
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
	 * @param name the bare name to intern
	 * @return the canonical spelling for the current package
	 */
	public String internSpelling(String name) {
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
	 * The spelling {@code intern} gives a bare name in a DESIGNATED package (the 2-arg
	 * {@code (intern name package)} form): the same accessibility rules as
	 * {@link #internSpelling}, evaluated with the designated package current. The
	 * {@code keyword} pseudo-package builds a keyword.
	 * @param packageDesignator the package name as given (any case, nickname allowed)
	 * @param name the bare name to intern
	 * @return the canonical spelling for that package
	 * @throws LispPackageException when no such package exists
	 */
	/**
	 * The external symbols of a designated package, as canonically spelled symbols in a
	 * stable (sorted) order -- the {@code do-external-symbols} iteration source. The
	 * registry records exports from {@code defpackage}, so the listing reflects every
	 * package registered so far.
	 * @param packageDesignator the package name as given (any case, nickname allowed)
	 * @return the external symbols, canonically spelled
	 * @throws LispPackageException when no such package exists
	 */
	public java.util.List<LispSymbol> externalSymbols(String packageDesignator) {
		String pkg = findPackageName(packageDesignator);
		if (pkg == null) {
			throw new LispPackageException("No such package: " + packageDesignator);
		}
		LispPackage p = this.registry.get(pkg);
		if (p == null) {
			return java.util.List.of();
		}
		java.util.List<String> names = new java.util.ArrayList<>(p.externals());
		java.util.Collections.sort(names);
		java.util.List<LispSymbol> out = new java.util.ArrayList<>(names.size());
		for (String name : names) {
			out.add(canonical(pkg, name));
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
			return internSpelling(name);
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
	 * The canonical name of the package a symbol lives in: the qualifier of a qualified
	 * spelling, {@code "keyword"} for a keyword, {@code cl} for a standard symbol,
	 * {@code cl-user} otherwise; null for an uninterned ({@code #:}) symbol. The runtime
	 * {@code symbol-package} answer, kept consistent with {@link #findPackageName} so the
	 * two are {@code eq}-comparable (ironclad's {@code massage-symbol} pattern).
	 * @param symbolName the stored symbol name
	 * @return the canonical package name, or null
	 */
	public @Nullable String symbolPackageName(String symbolName) {
		if (symbolName.startsWith("#:")) {
			return null;
		}
		if (symbolName.startsWith(":")) {
			return "keyword";
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		if (qn != null) {
			String found = findPackageName(qn.pkg());
			return found != null ? found : qn.pkg();
		}
		if (PackageRegistry.isClSymbol(symbolName)) {
			String cl = findPackageName(LispNames.CL_PKG);
			return cl != null ? cl : LispNames.CL_PKG;
		}
		String clUser = findPackageName(LispNames.CL_USER_PKG);
		return clUser != null ? clUser : LispNames.CL_USER_PKG;
	}

	/**
	 * The canonical spelling {@code (find-symbol name pkg)} yields, or null when the
	 * package does not provide the name. Rontolisp has no intern table, so "interned in
	 * the package" is judged by the registry: the package owns, exports or imports the
	 * (verbatim) name. The keyword pseudo-package builds the keyword; the {@code cl}
	 * package answers its static symbol set.
	 * @param pkgDesignator the package name as given
	 * @param member the verbatim symbol name
	 * @return the canonical spelling, or null
	 */
	public @Nullable String memberSpelling(String pkgDesignator, String member) {
		String pkg = findPackageName(pkgDesignator);
		if (pkg == null) {
			throw new LispPackageException("No such package: " + pkgDesignator);
		}
		if ("keyword".equals(pkg)) {
			return ":" + member;
		}
		if (LispNames.CL_PKG.equals(pkg)) {
			return PackageRegistry.isClSymbol(member) ? member : null;
		}
		if (LispNames.CL_USER_PKG.equals(pkg)) {
			return member;
		}
		LispPackage p = this.registry.get(pkg);
		if (p.owns(member) || p.exports(member) || p.imports().containsKey(member)) {
			// An imported (re-exported) member lives in its home package; redirect
			// like resolveQualified so find-symbol answers the same spelling a call
			// site resolves to (closer-common-lisp:class-slots IS
			// closer-mop:class-slots).
			String home = p.imports().get(member);
			if (home != null) {
				return LispNames.CL_PKG.equals(home) || LispNames.CL_USER_PKG.equals(home) ? member
						: canonical(home, member).name();
			}
			return canonical(pkg, member).name();
		}
		return null;
	}

	private static String operatorMember(LispSymbol op) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
		return qn == null ? op.name() : qn.member();
	}

	/** Builds a proper list from the given elements. */
	private static LispVal properListOf(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

	private static LispVal quotedSymbol(String name) {
		return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(new LispSymbol(name), LispNil.INSTANCE));
	}

}
