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
 * The {@code metal} package: a Metal drawing surface on an {@code appkit} window -- the
 * {@code CAMetalLayer} on the content view, the device, the command queue, the render
 * pass, the drawable, present and commit, plus the shader, pipeline and buffer helpers
 * every Metal program writes identically ({@code metal.lisp} on the classpath). Written
 * in rontolisp itself over the {@code objc:} verbs and shipped inside the interpreter,
 * the {@link AppKitLibrary} pattern.
 *
 * <p>
 * It was {@code examples/macos/metal.lisp} until {@code scene} needed it: a shipped
 * package cannot reach an example by relative path, and four examples already shared the
 * file, which is the same "a second consumer fixed the API" argument that promoted the
 * {@code appkit} rungs. It stays usable WITHOUT {@code geom} or {@code scene}: it is the
 * low-level surface those four examples drive directly.
 *
 * <p>
 * Consumers, the {@link AppKitLibrary} pair:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time a {@code metal:}-qualified function is resolved
 * ({@code LispEvaluator#resolveFunction});</li>
 * <li>the compile path ({@code CompileFrontend}) calls {@link #process(List)} BEFORE
 * {@code AppKitLibrary.process}, so the {@code appkit:timer} reference inside the spliced
 * {@code metal:run} pulls the widget layer in too, and their {@code objc:send} calls gate
 * the embedded {@code am.ik.objc} blob on
 * ({@code codegen.jvm.JvmObjcRuntimeBuilder}).</li>
 * </ul>
 * The WASM backends have no foreign function API, so {@code CompileFrontend} refuses a
 * {@code .wasm} output for a program that references this package
 * ({@link AppKitLibrary#firstObjcReference}) before any library is spliced.
 */
public final class MetalLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private MetalLibrary() {
	}

	/**
	 * Returns the parsed library definitions. Written in canonical shape (external
	 * single-colon public names, internal {@code metal::} helpers, bare {@code cl}
	 * names), so it needs no package resolution. Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (MetalLibrary.class) {
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
		try (InputStream in = MetalLibrary.class.getResourceAsStream("metal.lisp")) {
			if (in == null) {
				throw new IllegalStateException("metal.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the given symbol name references the {@code metal} package: any
	 * {@code metal:}/{@code metal::} qualified name.
	 * @param symbolName the symbol name as written
	 * @return {@code true} when the name is metal-qualified
	 */
	public static boolean isMetalQualified(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.METAL_PKG.equals(qn.pkg());
	}

	/**
	 * The compile-path pre-pass: when the program references the {@code metal} package (a
	 * {@code metal:}/{@code metal::} qualified symbol anywhere, or a bare exported name
	 * while {@code (in-package metal)} is in effect), prepends the library definitions. A
	 * program that does not use metal is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the metal library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		Walker walker = new Walker();
		for (LispVal form : program) {
			walker.trackTopLevelInPackage(form);
			walker.detect(form);
		}
		if (!walker.found) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(forms());
		out.addAll(program);
		return out;
	}

	private static final class Walker {

		private boolean found;

		private String currentPackage = LispNames.CL_USER_PKG;

		private void trackTopLevelInPackage(LispVal form) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& LispNames.IN_PACKAGE.equals(member(op.name())) && cons.cdr() instanceof LispCons argCell) {
				String name = switch (argCell.car()) {
					case LispSymbol sym -> sym.isKeyword() ? sym.name().substring(1) : sym.name();
					case LispString str -> str.value();
					default -> this.currentPackage;
				};
				this.currentPackage = PackageRegistry.canonicalBuiltinName(name);
			}
		}

		private static String member(String name) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(name);
			return qn == null ? name : qn.member();
		}

		private void detect(LispVal form) {
			if (this.found) {
				return;
			}
			switch (form) {
				case LispSymbol sym -> {
					if (isMetalQualified(sym.name()) || (LispNames.METAL_PKG.equals(this.currentPackage)
							&& PackageRegistry.metalFunctionNames().contains(sym.name().toUpperCase(Locale.ROOT)))) {
						this.found = true;
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

}
