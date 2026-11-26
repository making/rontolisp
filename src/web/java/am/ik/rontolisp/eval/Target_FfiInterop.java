package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link FfiInterop}, the counterpart of
 * {@link Target_ObjcInterop}. The browser playground has no foreign function API and no
 * C library. {@link FfiInterop#available()}, {@link FfiInterop#description()} and
 * {@link FfiInterop#register} are the only entry points into {@code FfiBridge} (the
 * holder of the single {@code am.ik.ffi} reference), so substituting all three makes
 * that class -- and the whole binding -- unreachable ({@code firstFfiReference} is pure
 * AST inspection and touches no bridge). Adding a public method to {@code FfiInterop}
 * that touches the bridge breaks that, and only the Pages workflow's Web Image build
 * would catch it.
 *
 * <p>
 * The {@code ffi:} verbs are still defined, so a program that reaches one fails at the
 * call with the truthful reason rather than with an undefined function.
 */
@TargetClass(FfiInterop.class)
final class Target_FfiInterop {

	@Substitute
	static boolean available() {
		return false;
	}

	@Substitute
	static String description() {
		return "no foreign function API in the browser playground";
	}

	@Substitute
	static void register(Environment globalEnv, FfiCaller caller) {
		for (String member : List.of(LispNames.FFI_OPEN, LispNames.FFI_SYMBOL, LispNames.FFI_CALL,
				LispNames.FFI_CALLBACK, LispNames.FFI_ALLOC, LispNames.FFI_FREE, LispNames.FFI_PEEK,
				LispNames.FFI_POKE, LispNames.FFI_SIZE, LispNames.FFI_ALIGN, LispNames.FFI_POINTERP,
				LispNames.FFI_ADDRESS, LispNames.FFI_ERRNO)) {
			String name = PackageRegistry.qualify(LispNames.FFI_PKG, member);
			globalEnv.defineFunction(name, new LispFunction(name, args -> {
				throw new LispEvalException(name.toLowerCase(java.util.Locale.ROOT)
						+ ": foreign functions are not available in the browser playground");
			}));
		}
	}

}
