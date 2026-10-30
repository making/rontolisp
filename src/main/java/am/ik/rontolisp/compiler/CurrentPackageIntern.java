package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.SourceProvenance;
import org.jspecify.annotations.Nullable;

/**
 * A one-argument {@code intern} / {@code find-symbol} reads {@code *package*} when it
 * RUNS; the compile paths' one-argument lowerings build a bare (cl-user) spelling. In a
 * program that switches to another package, this pass spells the default out:
 * {@code (intern x)} becomes {@code (intern x cl:*package*)} unless {@code *package*} is
 * {@code cl-user}, so the site takes the computed-designator lowering and the mention
 * keeps {@code *package*} live ({@code .kb/symbol-runtime-api.md}, "1-arg
 * {@code intern}"). The function object -- {@code #'intern}, or {@code 'intern} in a
 * function-designator argument -- becomes a lambda making the same call, so a mapped or
 * funcalled intern reads {@code *package*} too.
 *
 * <p>
 * It runs on the user program (the read program, its inlined loads and its expanded user
 * macros) before any library is spliced: the implementation's own Lisp sources use the
 * one-argument form to intern a canonical spelling verbatim, and the lowerings synthesize
 * that form later still. A program that never leaves {@code cl-user} is returned
 * unchanged -- there the bare build is the answer.
 */
public final class CurrentPackageIntern {

	private static final LispSymbol PACKAGE_VARIABLE = new LispSymbol(LispNames.CL_PKG + ":" + LispNames.PACKAGE_VAR);

	private static final LispSymbol NAME_VARIABLE = new LispSymbol("%CURRENT-PACKAGE-INTERN-NAME");

	private static final LispSymbol REST_VARIABLE = new LispSymbol("%CURRENT-PACKAGE-INTERN-PACKAGE");

	private CurrentPackageIntern() {
	}

	/**
	 * Spells out the {@code *package*} default of every one-argument {@code intern} /
	 * {@code find-symbol} call when the program switches to a package other than
	 * {@code cl-user}.
	 * @param program the top-level forms
	 * @return the rewritten forms, or {@code program} itself when nothing changes
	 */
	public static List<LispVal> rewrite(List<LispVal> program) {
		if (!switchesPackage(program)) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(program.size());
		boolean changed = false;
		for (LispVal form : program) {
			LispVal rewritten = rewriteForm(form);
			changed |= rewritten != form;
			out.add(rewritten);
		}
		return changed ? out : program;
	}

	private static boolean switchesPackage(List<LispVal> program) {
		for (LispVal form : program) {
			if (switchesPackage(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean switchesPackage(LispVal form) {
		LispVal node = form;
		while (node instanceof LispCons cons) {
			if (cons.car() instanceof LispSymbol head) {
				if (isClOperator(head, LispNames.QUOTE)) {
					return false;
				}
				if (isClOperator(head, LispNames.IN_PACKAGE) && cons.cdr() instanceof LispCons arg
						&& !isClUser(arg.car())) {
					return true;
				}
			}
			if (switchesPackage(cons.car())) {
				return true;
			}
			node = cons.cdr();
		}
		return false;
	}

	private static boolean isClUser(LispVal designator) {
		String name = switch (designator) {
			case LispString str -> str.value();
			case LispSymbol sym -> sym.name();
			default -> null;
		};
		if (name == null) {
			return false;
		}
		String bare = name.startsWith("#:") ? name.substring(2) : name.startsWith(":") ? name.substring(1) : name;
		String upper = bare.toUpperCase(Locale.ROOT);
		return LispNames.CL_USER_PKG.equals(upper) || "COMMON-LISP-USER".equals(upper);
	}

	private static LispVal rewriteForm(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol head) {
			if (isClOperator(head, LispNames.QUOTE)) {
				return form;
			}
			if (isInternOperator(head) && cons.cdr() instanceof LispCons arg && arg.cdr() instanceof LispNil) {
				return SourceProvenance.inherit(cons, currentPackageCall(head, rewriteForm(arg.car())));
			}
			if (isClOperator(head, LispNames.FUNCTION) && cons.cdr() instanceof LispCons arg
					&& arg.car() instanceof LispSymbol name && isInternOperator(name)) {
				return SourceProvenance.inherit(cons, currentPackageFunction(name));
			}
		}
		// Element-wise, rebuilding only a changed spine. A quoted 'intern in a function
		// designator position is #'intern.
		int designator = cons.car() instanceof LispSymbol head ? designatorArgument(head) : -1;
		List<LispVal> elements = new ArrayList<>();
		boolean changed = false;
		LispVal node = cons;
		while (node instanceof LispCons cell) {
			LispVal element = elements.size() == designator
					&& quotedInternOperator(cell.car()) instanceof LispSymbol name ? currentPackageFunction(name)
							: rewriteForm(cell.car());
			changed |= element != cell.car();
			elements.add(element);
			node = cell.cdr();
		}
		if (!changed) {
			return form;
		}
		LispVal result = node;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return SourceProvenance.inherit(cons, result);
	}

	// (if (eq cl:*package* :cl-user) (OP name) (OP name cl:*package*)): under cl-user the
	// one-argument lowering stays (a literal find-symbol folds, a fresh name is
	// :internal); elsewhere the computed-designator one runs. A name that is neither a
	// literal nor a variable is evaluated once, bound first.
	private static LispVal currentPackageCall(LispSymbol op, LispVal name) {
		boolean simple = name instanceof LispString || name instanceof LispSymbol;
		LispVal value = simple ? name : NAME_VARIABLE;
		LispVal choice = list(cl(LispNames.IF), list(cl("EQ"), PACKAGE_VARIABLE, new LispSymbol(":CL-USER")),
				list(op, value), list(op, value, PACKAGE_VARIABLE));
		return simple ? choice : list(cl(LispNames.LET), list(list(NAME_VARIABLE, name)), choice);
	}

	// The function object with the call's *package* default:
	// #'(lambda (name &rest p) (if p (OP name (car p)) <the call above>)).
	private static LispVal currentPackageFunction(LispSymbol op) {
		LispVal call = list(cl(LispNames.IF), REST_VARIABLE, list(op, NAME_VARIABLE, list(cl("CAR"), REST_VARIABLE)),
				currentPackageCall(op, NAME_VARIABLE));
		LispVal lambda = list(cl(LispNames.LAMBDA), list(NAME_VARIABLE, new LispSymbol("&REST"), REST_VARIABLE), call);
		return list(cl(LispNames.FUNCTION), lambda);
	}

	private static boolean isInternOperator(LispSymbol symbol) {
		return isClOperator(symbol, LispNames.INTERN) || isClOperator(symbol, LispNames.FIND_SYMBOL);
	}

	private static @Nullable LispSymbol quotedInternOperator(LispVal form) {
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& isClOperator(head, LispNames.QUOTE) && cons.cdr() instanceof LispCons arg
				&& arg.cdr() instanceof LispNil && arg.car() instanceof LispSymbol name && isInternOperator(name) ? name
						: null;
	}

	private static int designatorArgument(LispSymbol head) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(head.name());
		if (qn == null) {
			return FunctionDesignators.designatorArgument(head.name());
		}
		return LispNames.CL_PKG.equals(qn.pkg()) || "COMMON-LISP".equals(qn.pkg())
				? FunctionDesignators.designatorArgument(qn.member()) : -1;
	}

	private static LispSymbol cl(String member) {
		return new LispSymbol(LispNames.CL_PKG + ":" + member);
	}

	private static LispVal list(LispVal... elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.length - 1; i >= 0; i--) {
			result = new LispCons(elements[i], result);
		}
		return result;
	}

	// The operator spelled bare or cl-qualified, as the reader leaves it before package
	// resolution.
	private static boolean isClOperator(LispSymbol head, String member) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(head.name());
		if (qn == null) {
			return member.equals(head.name());
		}
		return member.equals(qn.member()) && (LispNames.CL_PKG.equals(qn.pkg()) || "COMMON-LISP".equals(qn.pkg()));
	}

}
