package am.ik.rontolisp.cli;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

/**
 * Detects a {@code rontolisp:http-handler} directive so the CLI can compile the program
 * as a serve-mode ({@code wasi:http/incoming-handler}) component.
 *
 * <p>
 * The HTTP glue itself is no longer synthesized here: under {@code --component} the
 * directive is turned into http.lisp's {@code %serve-handle} export by
 * {@code eval/HttpLibrary}, replacing the hand-written serve adapter. This class is just
 * the presence check the CLI uses to route a program down that path (and to reject
 * {@code rontolisp:http-handler} on Preview 1).
 */
public final class HttpHandlerInliner {

	private HttpHandlerInliner() {
	}

	/**
	 * Returns whether the program calls the {@code rontolisp:http-handler} directive --
	 * as a top-level form or NESTED inside a defun body (quoted data excluded). The
	 * nested shape is the {@code clack-handler-rontolisp} shim's: its {@code run} defun
	 * calls the directive with a literal quoted handler name, and
	 * {@code eval/HttpLibrary} extracts that name for the serve export exactly like a
	 * top-level directive's.
	 * @param program the top-level forms
	 * @return {@code true} if the program serves HTTP via {@code http-handler}
	 */
	public static boolean usesHttpHandler(List<LispVal> program) {
		for (LispVal form : program) {
			if (containsHttpHandlerCall(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsHttpHandlerCall(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol sym) {
			if (LispNames.QUOTE.equals(sym.name())) {
				// Quoted data is not a call site.
				return false;
			}
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
			if (qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.HTTP_HANDLER.equals(qn.member())) {
				return true;
			}
		}
		return containsHttpHandlerCall(cons.car()) || containsHttpHandlerCall(cons.cdr());
	}

}
