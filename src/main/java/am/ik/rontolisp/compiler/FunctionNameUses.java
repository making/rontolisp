package am.ik.rontolisp.compiler;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrees;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * Whether a program takes one of a few names as a FUNCTION: a call, a {@code #'name}, a
 * quoted symbol (a designator {@code funcall} or {@code apply} may be handed), a place a
 * {@code setf} or a modify macro writes. A variable or a binding merely NAMED like it
 * does not count: CLHS 11.1.2.1.2.1 lets a program bind {@code set} lexically, and
 * {@code eval/uri.lisp} does. Asked by both compile backends for the operators that force
 * the eval runtime ({@code set}, {@code symbol-value}, {@code fboundp}), where a mention
 * anywhere used to count and put the runtime and its spread dispatcher in every program
 * reaching {@code java.net.URI}.
 *
 * <p>
 * The walk is {@link CallSiteWalk}'s, so it knows which positions are code. Quoted data
 * and a macro definition still count every symbol they hold: a list built from data can
 * become a call, and a macro body is code no walk here sees expanded.
 */
public final class FunctionNameUses {

	private static final Set<String> PLACE_WRITERS = Set.of(LispNames.SETF, LispNames.PSETF, LispNames.INCF,
			LispNames.DECF, LispNames.POP, LispNames.REMF, LispNames.PUSH, LispNames.PUSHNEW, LispNames.ROTATEF,
			LispNames.SHIFTF);

	private FunctionNameUses() {
	}

	/**
	 * Whether the program takes any of {@code names} as a function.
	 * @param program the top-level forms
	 * @param names the symbol names, as {@link LispSymbol#name()} spells them
	 * @return true when some form calls, references or designates one of them
	 */
	public static boolean any(List<LispVal> program, Set<String> names) {
		Finder finder = new Finder(names);
		for (LispVal form : program) {
			CallSiteWalk.walk(form, finder);
			if (finder.found) {
				return true;
			}
		}
		return false;
	}

	private static final class Finder implements CallSiteWalk.Rewriter {

		private final Set<String> names;

		boolean found;

		Finder(Set<String> names) {
			this.names = names;
		}

		@Override
		public @Nullable LispVal call(LispCons form, LispSymbol head) {
			String op = head.name();
			if (LispNames.isQuote(op) || LispNames.DEFMACRO.equals(op) || LispNames.MACROLET.equals(op)) {
				this.found |= LispTrees.anyDatumNode(form,
						node -> node instanceof LispSymbol symbol && this.names.contains(symbol.name()));
				return form;
			}
			if (this.names.contains(op)) {
				this.found = true;
				return form;
			}
			if (LispNames.FUNCTION.equals(op) && form.cdr() instanceof LispCons named
					&& named.car() instanceof LispSymbol target) {
				this.found |= this.names.contains(target.name());
				return form;
			}
			if (PLACE_WRITERS.contains(op)) {
				for (LispVal cur = form.cdr(); cur instanceof LispCons cell; cur = cell.cdr()) {
					if (cell.car() instanceof LispCons place && place.car() instanceof LispSymbol placeHead
							&& this.names.contains(placeHead.name())) {
						this.found = true;
					}
				}
			}
			return null;
		}

	}

}
