package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The {@code appkit} package: a Cocoa widget layer -- {@code appkit:window},
 * {@code appkit:label}, {@code appkit:button} with a Lisp closure as its action,
 * {@code appkit:set-text} / {@code appkit:text}, {@code appkit:click},
 * {@code appkit:close}, {@code appkit:visible-p}, {@code appkit:wait} -- written in
 * rontolisp itself over the {@code objc:} verbs ({@code appkit.lisp} on the classpath)
 * and SHIPPED inside the interpreter, the {@code linalg} pattern: a user opens a bare
 * REPL and types {@code (appkit:window "hi")} with nothing required and nothing to copy.
 * That is the difference from {@code examples/jvm/swing.lisp}, a Lisp-level package a
 * consumer must splice itself.
 *
 * <p>
 * Consumers, the {@link LinalgLibrary} pair:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time an {@code appkit:}-qualified function is resolved
 * ({@code LispEvaluator#resolveFunction});</li>
 * <li>the JVM compile path ({@code CompileFrontend}) calls {@link #process(List)} after
 * user-macro expansion: when the program references the {@code appkit} package, the
 * library definitions are prepended, and their {@code objc:send} calls gate the embedded
 * {@code am.ik.objc} blob on ({@code codegen.jvm.JvmObjcRuntimeBuilder}).</li>
 * </ul>
 * The WASM backends have no foreign function API and never will, so
 * {@code CompileFrontend} refuses a {@code .wasm} output for a program that references
 * either package ({@link #firstObjcReference}) before any library is spliced.
 */
public final class AppKitLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private AppKitLibrary() {
	}

	/**
	 * Returns the parsed library definitions. Written in canonical shape (external
	 * single-colon public names, internal double-colon helpers, bare {@code cl} names),
	 * so it needs no package resolution. Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (AppKitLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readSource(), Features.INTERPRETER);
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = AppKitLibrary.class.getResourceAsStream("appkit.lisp")) {
			if (in == null) {
				throw new IllegalStateException("appkit.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the given symbol name references the {@code appkit} package: any
	 * {@code appkit:}/{@code appkit::} qualified name.
	 * @param symbolName the symbol name as written
	 * @return {@code true} when the name is appkit-qualified
	 */
	public static boolean isAppkitQualified(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.APPKIT_PKG.equals(qn.pkg());
	}

	/**
	 * The first reference to a macOS-only package in a program -- {@code objc},
	 * {@code appkit}, {@code metal} or {@code scene}, as a qualified symbol anywhere or
	 * as a bare exported name while {@code (in-package <that>)} is in effect -- or
	 * {@code null} when the program uses none of them. The compile path refuses a
	 * {@code .wasm} output on this answer, naming the reference. All four are one
	 * question because they are one refusal: every one of them bottoms out in
	 * {@code objc:send}, which no WASM backend has an API for.
	 * @param program the top-level forms
	 * @return the symbol as written, or {@code null}
	 */
	public static @Nullable String firstObjcReference(List<LispVal> program) {
		ReferenceWalker walker = new ReferenceWalker();
		for (LispVal form : program) {
			walker.trackTopLevelInPackage(form);
			walker.detect(form);
			if (walker.found != null) {
				return walker.found;
			}
		}
		return null;
	}

	/**
	 * Finds the FIRST reference to any of the four macOS-only packages and stops. Kept
	 * apart from {@link Walker}, which answers a different question (does this program
	 * need the appkit SPLICE) and may therefore stop earlier.
	 */
	private static final class ReferenceWalker {

		private @Nullable String found;

		private String currentPackage = LispNames.CL_USER_PKG;

		private void trackTopLevelInPackage(LispVal form) {
			this.currentPackage = trackInPackage(form, this.currentPackage);
		}

		private void detect(LispVal form) {
			if (this.found != null) {
				return;
			}
			switch (form) {
				case LispSymbol sym -> {
					PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
					if (qn != null) {
						if (MACOS_PACKAGES.contains(qn.pkg())) {
							this.found = sym.name();
						}
					}
					else if (exportedBy(this.currentPackage, sym.name())) {
						this.found = this.currentPackage + ":" + sym.name();
					}
				}
				case LispCons cons -> {
					detect(cons.car());
					detect(cons.cdr());
				}
				default -> {
				}
			}
		}

	}

	/**
	 * The packages that make a program macOS-only, and therefore un-compilable to WASM.
	 */
	private static final List<String> MACOS_PACKAGES = List.of(LispNames.OBJC_PKG, LispNames.APPKIT_PKG,
			LispNames.METAL_PKG, LispNames.SCENE_PKG);

	/** Whether {@code name} is an exported name of {@code pkg}, one of the four above. */
	private static boolean exportedBy(String pkg, String name) {
		String upper = name.toUpperCase(Locale.ROOT);
		if (LispNames.OBJC_PKG.equals(pkg)) {
			return OBJC_VERBS.contains(upper);
		}
		if (LispNames.APPKIT_PKG.equals(pkg)) {
			return PackageRegistry.appkitFunctionNames().contains(upper);
		}
		if (LispNames.METAL_PKG.equals(pkg)) {
			return PackageRegistry.metalFunctionNames().contains(upper);
		}
		if (LispNames.SCENE_PKG.equals(pkg)) {
			return PackageRegistry.sceneFunctionNames().contains(upper);
		}
		return false;
	}

	private static String trackInPackage(LispVal form, String current) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
				&& LispNames.IN_PACKAGE.equals(memberName(op.name())) && cons.cdr() instanceof LispCons argCell) {
			String name = switch (argCell.car()) {
				case LispSymbol sym -> sym.isKeyword() ? sym.name().substring(1) : sym.name();
				case LispString str -> str.value();
				default -> current;
			};
			return PackageRegistry.canonicalBuiltinName(name);
		}
		return current;
	}

	private static String memberName(String name) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
		return qn == null ? name : qn.member();
	}

	/**
	 * The compile-path pre-pass: when the program references the {@code appkit} package
	 * (an {@code appkit:}/{@code appkit::} qualified symbol anywhere, or a bare exported
	 * name while {@code (in-package appkit)} is in effect), prepends the library
	 * definitions. A program that does not use appkit -- one that uses {@code objc:}
	 * directly included -- is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the appkit library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		Walker walker = new Walker();
		for (LispVal form : program) {
			walker.trackTopLevelInPackage(form);
			walker.detect(form);
		}
		if (!walker.appkit) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(forms());
		out.addAll(program);
		return out;
	}

	private static final class Walker {

		/**
		 * Whether an {@code appkit} reference (not merely an {@code objc} one) was seen.
		 */
		private boolean appkit;

		private String currentPackage = LispNames.CL_USER_PKG;

		private void trackTopLevelInPackage(LispVal form) {
			this.currentPackage = trackInPackage(form, this.currentPackage);
		}

		private void detect(LispVal form) {
			if (this.appkit) {
				// Nothing left to learn: the library is needed.
				return;
			}
			switch (form) {
				case LispSymbol sym -> {
					PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
					if (qn != null) {
						if (LispNames.APPKIT_PKG.equals(qn.pkg())) {
							this.appkit = true;
						}
					}
					else if (LispNames.APPKIT_PKG.equals(this.currentPackage)
							&& PackageRegistry.appkitFunctionNames().contains(sym.name().toUpperCase(Locale.ROOT))) {
						this.appkit = true;
					}
				}
				case LispCons cons -> {
					detect(cons.car());
					detect(cons.cdr());
				}
				default -> {
				}
			}
		}

	}

	private static final List<String> OBJC_VERBS = List.of(LispNames.OBJC_CLASS, LispNames.OBJC_SEND,
			LispNames.OBJC_DEFINE_CLASS, LispNames.OBJC_ON_MAIN, LispNames.OBJC_STRING, LispNames.OBJC_ADDRESS,
			LispNames.OBJC_OBJECTP);

}
