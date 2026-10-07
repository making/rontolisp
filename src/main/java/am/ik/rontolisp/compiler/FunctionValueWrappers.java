package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;

/**
 * The {@code (setq name (lambda ...))} forms a compile path injects so built-ins work as
 * function values: the catalog's ({@link BuiltinFunctionWrappers#generate(Set, Set)}) and
 * those of the native built-ins the compilers lower only in call position
 * ({@link BuiltinFunctionWrappers#NATIVE_VALUE_FUNCTIONS}), derived from each one's call
 * shape. The second half lives here, above the catalog, because a native body is built by
 * {@link BuiltinCallArity} and {@link ShadowedBuiltins}, which read the catalog.
 */
public final class FunctionValueWrappers {

	private static final String IF_DOES_NOT_EXIST_KEYWORD = ":IF-DOES-NOT-EXIST";

	/** The native built-ins' wrapper forms, in injection order. */
	private static final List<Native> NATIVES = BuiltinFunctionWrappers.NATIVE_VALUE_FUNCTIONS.stream()
		.map(name -> new Native(name, nativeValue(name)))
		.toList();

	private FunctionValueWrappers() {
	}

	/**
	 * One native built-in's wrapper.
	 *
	 * @param name the operator
	 * @param form its {@code (setq name (lambda ...))}
	 */
	private record Native(String name, LispVal form) {
	}

	/**
	 * The wrapper forms to splice: the catalog's, then the native built-ins', each for a
	 * name the program does not define itself and the caller has not excluded.
	 * @param userDefinedNames names the program defines (its own definition wins)
	 * @param excludedNames names the caller's gates keep out
	 * @return the wrapper forms
	 */
	public static List<LispVal> generate(Set<String> userDefinedNames, Set<String> excludedNames) {
		List<LispVal> wrappers = new ArrayList<>(BuiltinFunctionWrappers.generate(userDefinedNames, excludedNames));
		for (Native wrapper : NATIVES) {
			if (!userDefinedNames.contains(wrapper.name()) && !excludedNames.contains(wrapper.name())) {
				wrappers.add(wrapper.form());
			}
		}
		return wrappers;
	}

	/**
	 * A native built-in's function value, derived from its call shape
	 * ({@link NativeCallShapes}): the required parameters, and -- where the shape takes
	 * more -- a tail dispatched by its length onto a direct call per count the shape
	 * admits, and onto the wrong-count {@code program-error} the interpreter reports for
	 * any other ({@link ShadowedBuiltins#tailDispatch}, which also checks {@code close}'s
	 * {@code :abort}). The two keyword-taking operators read their keyword tail as the
	 * catalog wrappers do ({@code getf}; an injected wrapper cannot carry a {@code &key}
	 * check, whose helpers are spliced before it exists): {@code load} forwards
	 * {@code :if-does-not-exist}, the one option that changes what happens, and
	 * {@code make-string-output-stream} drops {@code :element-type}, as its call position
	 * does.
	 */
	private static LispVal nativeValue(String name) {
		if (LispNames.LOAD.equals(name)) {
			return BuiltinFunctionWrappers.setqLambda(name, List.of("f", LispNames.LAMBDA_REST, "kw"),
					List.of(BuiltinFunctionWrappers.callV(name, new LispSymbol("f"),
							new LispSymbol(IF_DOES_NOT_EXIST_KEYWORD),
							BuiltinFunctionWrappers.getfKwDefault(IF_DOES_NOT_EXIST_KEYWORD, LispTrue.INSTANCE))));
		}
		if (LispNames.MAKE_STRING_OUTPUT_STREAM.equals(name)) {
			return BuiltinFunctionWrappers.setqLambda(name, List.of(LispNames.LAMBDA_REST, "kw"),
					List.of(BuiltinFunctionWrappers.call(name)));
		}
		BuiltinCallArity.Shape shape = Objects.requireNonNull(BuiltinCallArity.of(name), name);
		List<String> params = new ArrayList<>();
		for (int i = 0; i < shape.min(); i++) {
			params.add("a" + i);
		}
		if (!shape.paired() && shape.max() == shape.min()) {
			return BuiltinFunctionWrappers.setqLambda(name, params,
					List.of(BuiltinFunctionWrappers.call(name, params.toArray(String[]::new))));
		}
		List<LispVal> required = params.stream().<LispVal>map(LispSymbol::new).toList();
		LispVal body = ShadowedBuiltins.tailDispatch(name, required, new LispSymbol("r"), null);
		List<String> lambdaList = new ArrayList<>(params);
		lambdaList.add(LispNames.LAMBDA_REST);
		lambdaList.add("r");
		return BuiltinFunctionWrappers.setqLambda(name, lambdaList, List.of(body));
	}

}
