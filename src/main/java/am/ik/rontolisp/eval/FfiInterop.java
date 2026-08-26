package am.ik.rontolisp.eval;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * The interpreter's {@code ffi} package: the foreign primitives CFFI's backend
 * ({@code cffi-sys}) is written over -- plain C through the foreign function API, with no
 * JNI, no bundled artifact, no dependency and no reflection, which is what lets it run in
 * the {@code rontolisp} native binary. The surface is a handful of generic verbs named
 * after the foreign system, the exact analogue of {@code objc:}: {@code ffi:open},
 * {@code ffi:symbol}, {@code ffi:call}, {@code ffi:callback}, {@code ffi:alloc},
 * {@code ffi:free}, {@code ffi:peek}, {@code ffi:poke}, {@code ffi:size},
 * {@code ffi:align}, {@code ffi:pointerp}, {@code ffi:address}, {@code ffi:errno}.
 *
 * <p>
 * This class is the ONLY entry into {@link FfiBridge}, which holds the single reference
 * to {@code am.ik.ffi} -- the {@code ObjcInterop} / {@code ObjcBridge} shape, and for the
 * same reason: {@code src/web/java/.../Target_FfiInterop.java} substitutes these three
 * methods, and with them the whole binding leaves the browser playground's Web Image
 * build. Adding a public method here that touches the bridge breaks that cut.
 *
 * <p>
 * Every failure is a SIGNAL, never a decline: a machine without native access, a library
 * that will not open, a symbol that is not there, an operand that does not fit, and in a
 * native image a shape that was not registered at build time all raise an ordinary
 * {@code error} whose message starts with {@code ffi:} and says which of those it was.
 *
 * @see FfiBridge
 */
public final class FfiInterop {

	private FfiInterop() {
	}

	/**
	 * Whether foreign calls will work on this JVM. Never throws; the first call opens the
	 * binding.
	 * @return {@code true} when the {@code ffi:} functions will work
	 */
	public static boolean available() {
		try {
			return FfiBridge.available();
		}
		catch (Throwable ex) {
			return false;
		}
	}

	/**
	 * What was bound, or why nothing was, in one line.
	 * @return the description
	 */
	public static String description() {
		try {
			return FfiBridge.description();
		}
		catch (Throwable ex) {
			return "foreign functions are not available: " + ex;
		}
	}

	/**
	 * Defines the {@code ffi:} functions in the global environment. They are defined on
	 * every platform and signal at call time where native access is absent, so a program
	 * fails at the call that needed the foreign function rather than with an undefined
	 * function.
	 * @param globalEnv the global environment
	 * @param caller how a callback applies a Lisp function
	 */
	public static void register(Environment globalEnv, FfiCaller caller) {
		FfiBridge.register(globalEnv, caller);
	}

	/**
	 * The first reference to the {@code ffi} package in a program -- a qualified symbol
	 * anywhere, or a bare verb name while {@code (in-package ffi)} is in effect -- or
	 * {@code null} when the program never touches it. The compile path's WASM outputs
	 * refuse a program on this answer ({@code CompileFrontend}): there is no foreign
	 * function API in a WASM runtime and never will be. Pure AST inspection; it does not
	 * touch the bridge.
	 * @param program the top-level forms, after load inlining
	 * @return the symbol as written, or {@code null}
	 */
	public static @Nullable String firstFfiReference(List<LispVal> program) {
		String currentPackage = LispNames.CL_USER_PKG;
		for (LispVal form : program) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& LispNames.IN_PACKAGE.equals(member(op.name())) && cons.cdr() instanceof LispCons argCell) {
				String name = switch (argCell.car()) {
					case LispSymbol sym -> sym.isKeyword() ? sym.name().substring(1) : sym.name();
					case LispString str -> str.value();
					default -> currentPackage;
				};
				currentPackage = PackageRegistry.canonicalBuiltinName(name);
			}
			String found = detect(form, currentPackage);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static String member(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	private static @Nullable String detect(LispVal form, String currentPackage) {
		switch (form) {
			case LispSymbol sym -> {
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
				if (qn != null && LispNames.FFI_PKG.equals(qn.pkg())) {
					return sym.name();
				}
				if (qn == null && LispNames.FFI_PKG.equals(currentPackage)
						&& FFI_VERBS.contains(sym.name().toUpperCase(Locale.ROOT))) {
					return currentPackage + ":" + sym.name();
				}
			}
			case LispCons cons -> {
				String found = detect(cons.car(), currentPackage);
				if (found != null) {
					return found;
				}
				return detect(cons.cdr(), currentPackage);
			}
			default -> {
			}
		}
		return null;
	}

	private static final Set<String> FFI_VERBS = Set.of(LispNames.FFI_OPEN, LispNames.FFI_SYMBOL, LispNames.FFI_CALL,
			LispNames.FFI_CALLBACK, LispNames.FFI_ALLOC, LispNames.FFI_FREE, LispNames.FFI_PEEK, LispNames.FFI_POKE,
			LispNames.FFI_SIZE, LispNames.FFI_ALIGN, LispNames.FFI_POINTERP, LispNames.FFI_ADDRESS, LispNames.FFI_ERRNO,
			LispNames.FFI_APPLY_CALL);

}
