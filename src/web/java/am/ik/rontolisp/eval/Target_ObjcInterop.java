package am.ik.rontolisp.eval;

import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link ObjcInterop}, the counterpart of
 * {@link Target_LinalgGpu}. The browser playground has no foreign function API, no
 * {@code libobjc} and no AppKit. {@link ObjcInterop#available()},
 * {@link ObjcInterop#description()}, {@link ObjcInterop#registerPrimitives},
 * {@link ObjcInterop#mainThreadHandOverRequired()} and
 * {@link ObjcInterop#parkMainThread()} are the only entry points into
 * {@code ObjcPrimitives} (the holder of the {@code am.ik.objc} references), so
 * substituting all five makes that class -- and the whole binding -- unreachable. Adding
 * a public method to {@code ObjcInterop} that touches the primitives breaks that, and
 * only the Pages workflow's Web Image build would catch it.
 *
 * <p>
 * The primitives are still defined, so a program that reaches one fails at the call with
 * the truthful reason rather than with an undefined function.
 */
@TargetClass(ObjcInterop.class)
final class Target_ObjcInterop {

	@Substitute
	static boolean available() {
		return false;
	}

	@Substitute
	static String description() {
		return "no foreign function API in the browser playground";
	}

	@Substitute
	static void registerPrimitives(Environment globalEnv,
			java.util.function.BiFunction<am.ik.rontolisp.LispVal, java.util.List<am.ik.rontolisp.LispVal>, am.ik.rontolisp.LispVal> apply) {
		ObjcTargetSupport.unavailable(globalEnv, PackageRegistry.qualify(LispNames.OBJC_PKG, LispNames.OBJC_ON_MAIN));
		for (String name : LispNames.OBJC_PRIMITIVES) {
			ObjcTargetSupport.unavailable(globalEnv, name);
		}
	}

	@Substitute
	static boolean mainThreadHandOverRequired() {
		return false;
	}

	@Substitute
	static void parkMainThread() {
		throw new IllegalStateException("there is no main thread to park in the browser playground");
	}

}

/**
 * Helpers of {@link Target_ObjcInterop} live outside the {@code @TargetClass}: GraalVM 25
 * rejects any member of a target class that carries none of {@code @Delete},
 * {@code @Substitute}, {@code @AnnotateOriginal} or {@code @Alias}, and a helper has no
 * original in {@link ObjcInterop} to substitute.
 */
final class ObjcTargetSupport {

	private ObjcTargetSupport() {
	}

	static void unavailable(Environment globalEnv, String name) {
		globalEnv.defineFunction(name, new LispFunction(name, args -> {
			throw new LispEvalException(
					name.toLowerCase(java.util.Locale.ROOT) + ": Objective-C is not available in the browser playground");
		}));
	}

}
