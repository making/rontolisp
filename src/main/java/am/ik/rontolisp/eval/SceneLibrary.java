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
 * The {@code scene} package: a 3-D viewer for {@code geom} solids ({@code scene.lisp} on
 * the classpath) -- an orbit/pan/dolly camera, a ground grid, world and body axis triads,
 * solid/wireframe shading, {@code scene:fit} and an animation hook, over
 * {@link MetalLibrary}'s surface and {@link AppKitLibrary}'s window.
 *
 * <p>
 * It is the CONSUMER of {@code geom}, not a peer of it: {@code geom} is
 * backend-independent and ships everywhere, while this half is {@code objc:}-dependent
 * and macOS-only. It ships anyway, the way {@code appkit.lisp} does -- the binary is what
 * people install, and a binary user who has {@code geom} and cannot draw with it is in a
 * strange position ({@code .kb/objc.md}, "Where the line goes").
 *
 * <p>
 * Consumers, the {@link AppKitLibrary} pair:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time a {@code scene:}-qualified function is resolved
 * ({@code LispEvaluator#resolveFunction}); the {@code geom:}, {@code metal:},
 * {@code linalg:} and {@code appkit:} calls in their bodies load those libraries through
 * the same hook on first use;</li>
 * <li>the compile path ({@code CompileFrontend}) calls {@link #process(List)} FIRST of
 * the five, so the {@code geom:} / {@code metal:} / {@code linalg:} / {@code appkit:}
 * references inside the spliced scene definitions pull those libraries in too.</li>
 * </ul>
 * The WASM backends have no foreign function API, so {@code CompileFrontend} refuses a
 * {@code .wasm} output for a program that references this package
 * ({@link AppKitLibrary#firstObjcReference}) before any library is spliced.
 */
public final class SceneLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private SceneLibrary() {
	}

	/**
	 * Returns the parsed library definitions. Written in canonical shape (external
	 * single-colon public names, internal {@code scene::%} helpers, bare {@code cl}
	 * names), so it needs no package resolution. Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (SceneLibrary.class) {
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
		try (InputStream in = SceneLibrary.class.getResourceAsStream("scene.lisp")) {
			if (in == null) {
				throw new IllegalStateException("scene.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the given symbol name references the {@code scene} package: any
	 * {@code scene:}/{@code scene::} qualified name.
	 * @param symbolName the symbol name as written
	 * @return {@code true} when the name is scene-qualified
	 */
	public static boolean isSceneQualified(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.SCENE_PKG.equals(qn.pkg());
	}

	/**
	 * The compile-path pre-pass: when the program references the {@code scene} package (a
	 * {@code scene:}/{@code scene::} qualified symbol anywhere, or a bare exported name
	 * while {@code (in-package scene)} is in effect), prepends the library definitions. A
	 * program that does not use scene is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the scene library spliced in when used
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
					if (isSceneQualified(sym.name()) || (LispNames.SCENE_PKG.equals(this.currentPackage)
							&& PackageRegistry.sceneFunctionNames().contains(sym.name().toUpperCase(Locale.ROOT)))) {
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
