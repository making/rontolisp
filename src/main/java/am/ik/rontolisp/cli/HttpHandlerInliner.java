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
 * directive is turned into serve.lisp's {@code %serve-handle} export by
 * {@code eval/ServeLibrary} (the mirror of {@code eval/FetchLibrary}), replacing the
 * hand-written serve adapter. This class is just the presence check the CLI uses to route
 * a program down that path (and to reject {@code rontolisp:http-handler} on Preview 1).
 */
public final class HttpHandlerInliner {

	private HttpHandlerInliner() {
	}

	/**
	 * Returns whether any top-level form is a {@code rontolisp:http-handler} directive.
	 * @param program the top-level forms
	 * @return {@code true} if the program serves HTTP via {@code http-handler}
	 */
	public static boolean usesHttpHandler(List<LispVal> program) {
		for (LispVal form : program) {
			if (isHttpHandlerForm(form)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isHttpHandlerForm(LispVal form) {
		if (!(form instanceof LispCons cons) || !(cons.car() instanceof LispSymbol sym)) {
			return false;
		}
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
		return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.HTTP_HANDLER.equals(qn.member());
	}

}
