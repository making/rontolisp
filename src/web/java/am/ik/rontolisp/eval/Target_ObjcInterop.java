package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.PackageRegistry;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Web Image substitution for {@link ObjcInterop}, the counterpart of
 * {@link Target_LinalgGpu}. The browser playground has no foreign function API, no
 * {@code libobjc} and no AppKit. {@link ObjcInterop#available()},
 * {@link ObjcInterop#description()}, {@link ObjcInterop#register},
 * {@link ObjcInterop#mainThreadHandOverRequired()} and
 * {@link ObjcInterop#parkMainThread()} are the only entry points into
 * {@code ObjcBridge} (the holder of the single {@code am.ik.objc} reference), so
 * substituting all five makes that class -- and the whole binding -- unreachable. Adding
 * a public method to {@code ObjcInterop} that touches the bridge breaks that, and only
 * the Pages workflow's Web Image build would catch it.
 *
 * <p>
 * The {@code objc:} verbs are still defined, so a program that reaches one fails at the
 * call with the truthful reason rather than with an undefined function.
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
	static void register(Environment globalEnv, ObjcCaller caller) {
		for (String member : List.of(LispNames.OBJC_CLASS, LispNames.OBJC_SEND, LispNames.OBJC_DEFINE_CLASS,
				LispNames.OBJC_ON_MAIN, LispNames.OBJC_STRING, LispNames.OBJC_ADDRESS, LispNames.OBJC_OBJECTP)) {
			String name = PackageRegistry.qualify(LispNames.OBJC_PKG, member);
			globalEnv.defineFunction(name, new LispFunction(name, args -> {
				throw new LispEvalException(
						name.toLowerCase(java.util.Locale.ROOT) + ": Objective-C is not available in the browser playground");
			}));
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
