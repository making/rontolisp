package am.ik.rontolisp;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves package-qualified and unqualified symbols against a {@link PackageRegistry}
 * and enforces the package discipline, as a read/compile-time pass that runs before the
 * evaluator and the compilers. It tracks the current package (driven by
 * {@code in-package} directives) and rewrites each top-level form into a canonical shape:
 *
 * <ul>
 * <li>{@code cl} standard symbols and {@code cl-user} user symbols become bare names (so
 * the existing evaluator/compilers handle them unchanged);</li>
 * <li>symbols of non-default packages become qualified {@code pkg:name} names (e.g.
 * {@code rontolisp:version});</li>
 * <li>{@code *package*} is replaced by a quoted symbol naming the current package;</li>
 * <li>{@code (in-package P)} is consumed and replaced by a quoted package symbol.</li>
 * </ul>
 *
 * An unqualified {@code cl} symbol used in a package that does not use {@code cl} (such
 * as {@code rontolisp}) is the single hard error. The instance keeps the current-package
 * state across calls, so a REPL session keeps {@code in-package} in effect across inputs.
 */
public final class PackageResolver {

	private final PackageRegistry registry;

	private String currentPackage = LispNames.CL_USER_PKG;

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
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& LispNames.IN_PACKAGE.equals(operatorMember(op))) {
			return resolveInPackage(cons);
		}
		return resolveForm(form);
	}

	private LispVal resolveInPackage(LispCons cons) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new LispPackageException(LispNames.IN_PACKAGE + " expects exactly one argument");
		}
		String name = packageDesignator(LispNames.IN_PACKAGE, parts.get(1));
		if (!this.registry.contains(name)) {
			throw new LispPackageException("No such package: " + name);
		}
		this.currentPackage = name;
		return quotedSymbol(name);
	}

	private static String packageDesignator(String context, LispVal designator) {
		return switch (designator) {
			// A keyword (:cl-user) or a bare symbol (cl-user); strip a leading colon.
			case LispSymbol sym -> sym.isKeyword() ? sym.name().substring(1) : sym.name();
			case LispString str -> str.value();
			default -> throw new LispPackageException(context + " expects a package name, got " + designator.print());
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
			// (quote DATUM): the operator is exempt and the datum is left untouched.
			LispVal datum = ((LispCons) cons.cdr()).car();
			return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(datum, LispNil.INSTANCE));
		}
		LispVal car = resolveForm(cons.car());
		if (car instanceof LispSymbol op) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
			if (qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && isIntrospectionMember(qn.member())) {
				return resolveIntrospection(op, qn.member(), cons);
			}
		}
		LispVal cdr = resolveForm(cons.cdr());
		return new LispCons(car, cdr);
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

	private LispVal resolveSymbol(LispSymbol sym) {
		if (sym.isKeyword()) {
			return sym;
		}
		// Lambda-list keywords (&rest, &optional, &key, ...) are structural markers,
		// not package-scoped symbols; they pass through like keywords.
		if (sym.name().startsWith("&")) {
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
		String pkg = qn.pkg();
		String member = qn.member();
		if (!this.registry.contains(pkg)) {
			throw new LispPackageException("No such package: " + pkg);
		}
		if (LispNames.CL_PKG.equals(pkg) && LispNames.PACKAGE_VAR.equals(member)) {
			return quotedSymbol(this.currentPackage);
		}
		// cl and cl-user are normalized to bare names; other packages keep the qualified
		// canonical name.
		if (LispNames.CL_PKG.equals(pkg) || LispNames.CL_USER_PKG.equals(pkg)) {
			return new LispSymbol(member);
		}
		return new LispSymbol(PackageRegistry.qualify(pkg, member));
	}

	private LispVal resolveUnqualified(String name) {
		if (LispNames.PACKAGE_VAR.equals(name)) {
			if (currentUsesCl()) {
				return quotedSymbol(this.currentPackage);
			}
			throw new LispPackageException(
					"Undefined symbol: " + name + " (use " + PackageRegistry.qualify(LispNames.CL_PKG, name) + ")");
		}
		if (PackageRegistry.isClSymbol(name)) {
			if (currentUsesCl()) {
				return new LispSymbol(name);
			}
			throw new LispPackageException(
					"Undefined symbol: " + name + " (use " + PackageRegistry.qualify(LispNames.CL_PKG, name) + ")");
		}
		LispPackage current = this.registry.get(this.currentPackage);
		if (current.owns(name)) {
			return canonical(this.currentPackage, name);
		}
		for (String used : current.useList()) {
			if (LispNames.CL_PKG.equals(used)) {
				continue;
			}
			if (this.registry.get(used).owns(name)) {
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

	private static LispSymbol canonical(String pkg, String name) {
		if (LispNames.CL_USER_PKG.equals(pkg)) {
			return new LispSymbol(name);
		}
		return new LispSymbol(PackageRegistry.qualify(pkg, name));
	}

	private static String operatorMember(LispSymbol op) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(op.name());
		return qn == null ? op.name() : qn.member();
	}

	private static LispVal quotedSymbol(String name) {
		return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(new LispSymbol(name), LispNil.INSTANCE));
	}

}
