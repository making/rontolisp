package am.ik.rontolisp.macro;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

/**
 * The definition-time capture of the {@code (funcall (compile nil `(lambda () ,code)))}
 * idiom (postmodern's {@code build-dao-methods} {@code %eval}): a no-argument lambda
 * whose body defines methods specialized on class METAOBJECTS spliced into the tree as
 * literals. The evaluator's {@code compile} intercepts that shape and, before running or
 * capturing it, folds every class-metaobject literal into a static reference the shared
 * expansion machinery understands:
 *
 * <ul>
 * <li>a specializer-position metaobject becomes the class NAME symbol
 * ({@code ((object #&lt;dao-class users&gt;))} to {@code ((object users))});</li>
 * <li>an {@code (eql (class-name #&lt;metaobject&gt;))} specializer folds to the literal
 * {@code (eql 'name)} (the form is evaluated at definition time in CL, and the class name
 * is static);</li>
 * <li>every other metaobject occurrence becomes {@code (find-class 'name)} -- the driver
 * registers the metaobject BEFORE {@code finalize-inheritance} runs (like CL's
 * {@code ensure-class}), so the lookup answers the same object whether the folded form is
 * evaluated during finalization (interpreter) or as a spliced top-level form after the
 * driver call (compiled program).</li>
 * </ul>
 *
 * The folded body is then evaluated in place on the interpreter, or recorded through the
 * macro-time evaluator's splice sink and appended to the program as top-level forms on
 * the compile paths ("expand and splice") -- where
 * {@code LispMacroExpander.expandLetNestedDefmethods} registers the nested
 * {@code defmethod}s statically and the closure machinery carries the lexical captures.
 */
public final class MopEvalCapture {

	private MopEvalCapture() {
	}

	/**
	 * Whether the form contains a {@code defmethod} anywhere outside quoted data -- the
	 * trigger for the definition-time capture (and the shape the generated
	 * {@code compile} runtime treats as already expanded, see {@code CompileRuntime}).
	 * @param form the definition to inspect
	 * @return true when a defmethod is present
	 */
	public static boolean definesMethods(LispVal form) {
		if (!(form instanceof LispCons cons)) {
			return false;
		}
		if (cons.car() instanceof LispSymbol op) {
			String member = memberOf(op.name());
			if (LispNames.QUOTE.equals(member)) {
				return false;
			}
			if (LispNames.DEFMETHOD.equals(member)) {
				return true;
			}
		}
		return definesMethods(cons.car()) || definesMethods(cons.cdr());
	}

	/**
	 * Folds every class-metaobject literal in the form into its static reference (see the
	 * class comment). Quoted data is left untouched; a form without metaobject literals
	 * is returned structurally unchanged.
	 * @param form the captured definition
	 * @param closRegistry the registry answering {@code isClassMetaobject}
	 * @return the folded form
	 */
	public static LispVal foldClassMetaobjects(LispVal form, ClosRegistry closRegistry) {
		if (form instanceof LispInstance inst && closRegistry.isClassMetaobject(inst)) {
			return findClassForm(inst);
		}
		if (!(form instanceof LispCons cons)) {
			return form;
		}
		if (cons.car() instanceof LispSymbol op) {
			String member = memberOf(op.name());
			if (LispNames.QUOTE.equals(member)) {
				return form;
			}
			if (LispNames.DEFMETHOD.equals(member) && cons.isProperList()) {
				return foldDefmethod(cons, closRegistry);
			}
		}
		return new LispCons(foldClassMetaobjects(cons.car(), closRegistry),
				foldClassMetaobjects(cons.cdr(), closRegistry));
	}

	// (defmethod name [qualifier] (params...) body...): fold the lambda list's
	// specializer positions to names/eql literals, then walk the body generally.
	private static LispVal foldDefmethod(LispCons defmethod, ClosRegistry closRegistry) {
		List<LispVal> parts = defmethod.toList();
		int llIndex = parts.size() > 2 && parts.get(2) instanceof LispSymbol ? 3 : 2;
		if (parts.size() <= llIndex || !(parts.get(llIndex) instanceof LispCons lambdaList)
				|| !lambdaList.isProperList()) {
			return defmethod; // malformed; the expansion reports it
		}
		List<LispVal> folded = new ArrayList<>(parts.subList(0, llIndex));
		List<LispVal> foldedParams = new ArrayList<>();
		for (LispVal param : lambdaList.toList()) {
			foldedParams.add(foldSpecializedParam(param, closRegistry));
		}
		folded.add(properList(foldedParams));
		for (int i = llIndex + 1; i < parts.size(); i++) {
			folded.add(foldClassMetaobjects(parts.get(i), closRegistry));
		}
		return properList(folded);
	}

	// (var #<metaobject>) -> (var name); (var (eql (class-name #<metaobject>))) ->
	// (var (eql 'name)). Every other parameter shape stays verbatim.
	private static LispVal foldSpecializedParam(LispVal param, ClosRegistry closRegistry) {
		if (!(param instanceof LispCons paramCons) || !paramCons.isProperList() || paramCons.toList().size() != 2) {
			return param;
		}
		List<LispVal> pair = paramCons.toList();
		LispVal spec = pair.get(1);
		if (spec instanceof LispInstance inst && closRegistry.isClassMetaobject(inst)) {
			return properList(List.of(pair.get(0), metaobjectName(inst)));
		}
		if (spec instanceof LispCons specCons && specCons.isProperList() && specCons.toList().size() == 2
				&& specCons.car() instanceof LispSymbol eqlSym && LispNames.EQL.equals(memberOf(eqlSym.name()))
				&& specCons.toList().get(1) instanceof LispCons eqlForm && eqlForm.isProperList()
				&& eqlForm.toList().size() == 2 && eqlForm.car() instanceof LispSymbol classNameSym
				&& LispNames.CLASS_NAME.equals(memberOf(classNameSym.name()))
				&& eqlForm.toList().get(1) instanceof LispInstance inst && closRegistry.isClassMetaobject(inst)) {
			return properList(List.of(pair.get(0), properList(List.of(new LispSymbol(LispNames.EQL),
					properList(List.of(new LispSymbol(LispNames.QUOTE), metaobjectName(inst)))))));
		}
		return param;
	}

	// (find-class 'name): the metaobject's static reference in an expression position.
	private static LispVal findClassForm(LispInstance metaobject) {
		return properList(List.of(new LispSymbol(LispNames.FIND_CLASS),
				properList(List.of(new LispSymbol(LispNames.QUOTE), metaobjectName(metaobject)))));
	}

	// The metaobject's name slot (index 0 of the seeded %obj-ref contract).
	private static LispVal metaobjectName(LispInstance metaobject) {
		return metaobject.slot(0);
	}

	private static String memberOf(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	private static LispVal properList(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

}
