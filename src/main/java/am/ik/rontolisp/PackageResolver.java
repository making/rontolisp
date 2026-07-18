package am.ik.rontolisp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
			if (LispNames.PUSH_PACKAGE.equals(member)) {
				pushPackage();
				return quotedSymbol(this.currentPackage);
			}
			if (LispNames.POP_PACKAGE.equals(member)) {
				popPackage();
				return quotedSymbol(this.currentPackage);
			}
		}
		return resolveForm(form);
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
		String name = this.registry.canonicalName(packageDesignator(LispNames.IN_PACKAGE, parts.get(1)));
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
	 * ignored. {@code :shadow}/{@code :shadowing-import-from} (and any other clause) are
	 * an error.
	 */
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
						String used = this.registry
							.canonicalName(designator(LispNames.USE_KEYWORD, "a package name", arg));
						if (!this.registry.contains(used)) {
							throw new LispPackageException("No such package: " + used);
						}
						if (!useList.contains(used)) {
							useList.add(used);
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
				case LispNames.IMPORT_FROM_KEYWORD -> {
					if (args.size() < 2) {
						throw new LispPackageException(LispNames.IMPORT_FROM_KEYWORD + " expects a package name");
					}
					String source = this.registry
						.canonicalName(designator(LispNames.IMPORT_FROM_KEYWORD, "a package name", args.get(1)));
					if (!this.registry.contains(source)) {
						throw new LispPackageException("No such package: " + source);
					}
					for (LispVal arg : args.subList(2, args.size())) {
						imports.put(designator(LispNames.IMPORT_FROM_KEYWORD, "a symbol name", arg), source);
					}
				}
				// Metadata: accepted for portability, not recorded anywhere.
				case ":documentation", ":size" -> {
				}
				case ":shadow", ":shadowing-import-from" -> throw new LispPackageException(LispNames.DEFPACKAGE + " "
						+ keyword.name() + " is not supported (rontolisp has no symbol shadowing)");
				default -> throw new LispPackageException(
						"Unsupported " + LispNames.DEFPACKAGE + " clause: " + keyword.name());
			}
		}
		this.registry.define(new LispPackage(name, List.copyOf(useList), Set.copyOf(exports), Set.copyOf(exports),
				Map.copyOf(imports)));
		for (String nickname : nicknames) {
			this.registry.defineNickname(nickname, name);
		}
		return quotedSymbol(name);
	}

	private static String packageDesignator(String context, LispVal designator) {
		return designator(context, "a package name", designator);
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
			default -> form;
		};
	}

	private LispVal resolveCons(LispCons cons) {
		if (cons.car() instanceof LispSymbol op && LispNames.QUOTE.equals(operatorMember(op))) {
			LispVal datum = ((LispCons) cons.cdr()).car();
			// (quote DATUM): the operator is exempt and the datum is left untouched --
			// except inside a defmacro/macrolet definition, where quoted symbols come
			// from backquote templates (the reader expands every backquote level into
			// list/cons/quote calls before this pass runs) and belong to the defining
			// package: a bare template symbol must resolve to the same canonical
			// spelling as the package-qualified defun/local-function it names.
			if (this.inMacroDefinition) {
				datum = resolveQuotedData(datum);
			}
			return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(datum, LispNil.INSTANCE));
		}
		if (cons.car() instanceof LispSymbol rawOp && LispNames.DEFPACKAGE.equals(operatorMember(rawOp))) {
			// resolveCons only sees non-top-level forms (resolve() consumes the
			// top-level directive), so a defpackage here is out of place.
			throw new LispPackageException(LispNames.DEFPACKAGE + " is only supported as a literal top-level form");
		}
		if (cons.car() instanceof LispSymbol macroOp && LispNames.DEFMACRO.equals(operatorMember(macroOp))) {
			// The whole definition is template context (see the quote case above).
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
		LispVal cdr = resolveForm(cons.cdr());
		return new LispCons(car, cdr);
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
			return new LispCons(op, new LispCons(resolvedName, resolveForm(nameCell.cdr())));
		}
		return new LispCons(op, resolveForm(cons.cdr()));
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

	// Resolves every symbol inside a quoted backquote-template datum (recursively
	// through conses); non-symbol atoms pass through. Only used inside
	// defmacro/macrolet definitions -- see resolveCons.
	private LispVal resolveQuotedData(LispVal datum) {
		return switch (datum) {
			case LispSymbol sym -> resolveSymbol(sym);
			case LispCons c -> new LispCons(resolveQuotedData(c.car()), resolveQuotedData(c.cdr()));
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
		String pkg = this.registry.canonicalName(qn.pkg());
		String member = qn.member();
		if (!this.registry.contains(pkg)) {
			throw new LispPackageException("No such package: " + pkg);
		}
		if (LispNames.CL_PKG.equals(pkg) && LispNames.PACKAGE_VAR.equals(member)) {
			return quotedSymbol(this.currentPackage);
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
		// cl and cl-user are normalized to bare names; other packages keep the qualified
		// canonical name.
		if (LispNames.CL_PKG.equals(pkg) || LispNames.CL_USER_PKG.equals(pkg)) {
			return new LispSymbol(member);
		}
		return canonical(pkg, member);
	}

	private boolean isExternal(String pkg, String member) {
		if (LispNames.CL_PKG.equals(pkg) && LispMacroExpander.isCarCdrComposition(member)) {
			return true;
		}
		return this.registry.get(pkg).exports(member);
	}

	private LispVal resolveUnqualified(String name) {
		if (LispNames.PACKAGE_VAR.equals(name)) {
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
		if (PackageRegistry.isClSymbol(name)) {
			if (currentUsesCl()) {
				return new LispSymbol(name);
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
			if (this.registry.get(used).exports(name)) {
				return canonical(used, name);
			}
		}
		// Unknown symbol: a user definition or forward reference in the current package.
		return canonical(this.currentPackage, name);
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

	private static String operatorMember(LispSymbol op) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
		return qn == null ? op.name() : qn.member();
	}

	private static LispVal quotedSymbol(String name) {
		return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(new LispSymbol(name), LispNil.INSTANCE));
	}

}
