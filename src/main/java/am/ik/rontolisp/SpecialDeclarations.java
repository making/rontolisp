package am.ik.rontolisp;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The {@code (declare (special ...))} declarations at the head of a body, read the way
 * CLHS 3.3.4 scopes them: a declaration naming a variable the form binds makes that
 * binding special, and every name it lists makes the references in the body special,
 * until an inner binding of the name shadows it -- an inner binding is lexical unless it
 * is declared special itself. A name proclaimed special ({@code defvar}, {@code declaim})
 * is special everywhere and needs none of this; a local declaration is how a name that is
 * not becomes special for one binding (cl-ppcre's convert phase, trivia's flags). The
 * interpreter reads the declarations of each binding form it evaluates, the compile paths
 * rename the lexical bindings of such a name apart before they compile
 * ({@code compiler.SpecialDeclarationScoping}), and the binding macros keep a special
 * declaration with the binding it names ({@link #hoisted}).
 *
 * <p>
 * The {@code declare} and {@code special} heads are matched by member name: under
 * {@code (in-package p)} the resolver spells a symbol that is not a registered {@code cl}
 * symbol {@code p::special}.
 */
public final class SpecialDeclarations {

	private SpecialDeclarations() {
	}

	/**
	 * Whether the form is a {@code (declare ...)} form.
	 * @param form the form
	 * @return whether its head is {@code declare}
	 */
	public static boolean isDeclaration(LispVal form) {
		// The suffix test first: the interpreter asks this of every let body it enters.
		return form instanceof LispCons cons && cons.car() instanceof LispSymbol head
				&& head.name().endsWith(LispNames.DECLARE) && LispNames.DECLARE.equals(member(head.name()));
	}

	/**
	 * The names the declarations at the head of a body declare special.
	 * @param body the body forms, a cons chain
	 * @param functionBody whether the body is a function's: a documentation string may
	 * sit among its declarations, and a body that is one {@code block} (or
	 * {@code %fn-block}) form -- what a {@code defun}, a {@code defmethod} and a local
	 * function wrap theirs in -- is read inside that form
	 * @return the names, in order; empty when there are none
	 */
	public static Set<String> leading(LispVal body, boolean functionBody) {
		LispVal cell = body;
		if (functionBody && cell instanceof LispCons only && only.cdr() instanceof LispNil) {
			LispVal inner = blockBody(only.car());
			if (inner != null) {
				cell = inner;
			}
		}
		@Nullable Set<String> names = null;
		while (cell instanceof LispCons c) {
			LispVal form = c.car();
			if (isDeclaration(form)) {
				names = addSpecials(form, names);
			}
			else if (!(functionBody && form instanceof LispString && c.cdr() instanceof LispCons)) {
				break;
			}
			cell = c.cdr();
		}
		return names == null ? Set.of() : names;
	}

	/**
	 * {@link #leading(LispVal, boolean)} over a body held as a list.
	 * @param body the body forms
	 * @param functionBody whether the body is a function's
	 * @return the names, in order; empty when there are none
	 */
	public static Set<String> leading(List<LispVal> body, boolean functionBody) {
		if (body.isEmpty()) {
			return Set.of();
		}
		if (functionBody && body.size() == 1) {
			LispVal inner = blockBody(body.get(0));
			if (inner != null) {
				return leading(inner, true);
			}
		}
		@Nullable Set<String> names = null;
		for (int i = 0; i < body.size(); i++) {
			LispVal form = body.get(i);
			if (isDeclaration(form)) {
				names = addSpecials(form, names);
			}
			else if (!(functionBody && form instanceof LispString && i + 1 < body.size())) {
				break;
			}
		}
		return names == null ? Set.of() : names;
	}

	/**
	 * The special declaration a binding macro puts at the head of the binding form it
	 * expands into, when the body it was given declares a name special: the expansion
	 * moves the body away from the binding (into a loop, a {@code tagbody}, a
	 * {@code block}), where the declaration would no longer be the binding's. Nothing --
	 * so the expansion is the one it always was -- for a body that declares none.
	 * @param body the macro's body forms
	 * @param functionBody whether the body is a function's
	 * @return {@code (declare (special ...))} as a one-element list, or an empty list
	 */
	public static List<LispVal> hoisted(List<LispVal> body, boolean functionBody) {
		Set<String> names = leading(body, functionBody);
		return names.isEmpty() ? List.of() : List.of(declaration(names));
	}

	/**
	 * {@code (declare (special names...))}.
	 * @param names the names, in order
	 * @return the declaration form
	 */
	public static LispVal declaration(Collection<String> names) {
		LispVal specials = LispNil.INSTANCE;
		List<String> ordered = List.copyOf(names);
		for (int i = ordered.size() - 1; i >= 0; i--) {
			specials = new LispCons(new LispSymbol(ordered.get(i)), specials);
		}
		return new LispCons(new LispSymbol(LispNames.DECLARE),
				new LispCons(new LispCons(new LispSymbol(LispNames.SPECIAL), specials), LispNil.INSTANCE));
	}

	/** The forms after {@code (block name} / {@code (%fn-block name}, or null. */
	private static @Nullable LispVal blockBody(LispVal form) {
		if (form instanceof LispCons block && block.car() instanceof LispSymbol head
				&& (LispNames.BLOCK.equals(head.name()) || LispNames.FN_BLOCK_INTERNAL.equals(head.name()))
				&& block.cdr() instanceof LispCons nameCell) {
			return nameCell.cdr();
		}
		return null;
	}

	private static @Nullable Set<String> addSpecials(LispVal declaration, @Nullable Set<String> names) {
		for (LispVal spec = ((LispCons) declaration).cdr(); spec instanceof LispCons cell; spec = cell.cdr()) {
			if (cell.car() instanceof LispCons clause && clause.car() instanceof LispSymbol op
					&& LispNames.SPECIAL.equals(member(op.name()))) {
				for (LispVal n = clause.cdr(); n instanceof LispCons nameCell; n = nameCell.cdr()) {
					if (nameCell.car() instanceof LispSymbol name && !name.isKeyword()) {
						if (names == null) {
							names = new LinkedHashSet<>();
						}
						names.add(name.name());
					}
				}
			}
		}
		return names;
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

}
