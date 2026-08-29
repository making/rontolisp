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
 * The {@code geom} package: solid modeling -- rigid {@code geom:transform} values, a
 * scene-graph {@code geom:node}, boundary-represented {@code geom:solid}s with their
 * cached model-space triangle mesh, the primitive constructors
 * ({@code geom:box}/{@code geom:cylinder}/{@code geom:sphere}/...) and the measurements
 * ({@code geom:bounds}/{@code geom:volume}/{@code geom:centroid}/
 * {@code geom:surface-area}) -- written in rontolisp itself ({@code geom.lisp} on the
 * classpath) over the {@code linalg} kernels and nothing else.
 *
 * <p>
 * Unlike {@link AppKitLibrary} this one is backend-INDEPENDENT: it reaches for no
 * {@code objc:}, no {@code java:} and no filesystem, so it compiles to both WASM backends
 * and runs in the browser playground as well as on the interpreter and the JVM
 * ({@code .kb/geom.md}).
 *
 * <p>
 * Consumers, the {@link LinalgLibrary} pair:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time a {@code geom:}-qualified function is resolved
 * ({@code LispEvaluator#resolveFunction}); the {@code linalg:} calls in their bodies load
 * the linalg library through the same hook on first use;</li>
 * <li>the compile path ({@code CompileFrontend}, the web playground and tests that drive
 * the compilers directly) calls {@link #process(List)} BEFORE
 * {@code LinalgLibrary.process}, so the {@code linalg:} references inside the spliced
 * geom definitions pull the linalg library in too.</li>
 * </ul>
 */
public final class GeomLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private GeomLibrary() {
	}

	/**
	 * Returns the parsed library definitions. Written in canonical shape (external
	 * single-colon public names, internal {@code geom::%} helpers, bare {@code cl}
	 * names), so it needs no package resolution and re-resolving it is a no-op. Parsed
	 * once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (GeomLibrary.class) {
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
		try (InputStream in = GeomLibrary.class.getResourceAsStream("geom.lisp")) {
			if (in == null) {
				throw new IllegalStateException("geom.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the given symbol name references the {@code geom} package: any
	 * {@code geom:}/{@code geom::} qualified name.
	 * @param symbolName the symbol name as written
	 * @return {@code true} when the name is geom-qualified
	 */
	public static boolean isGeomQualified(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.GEOM_PKG.equals(qn.pkg());
	}

	/**
	 * The four CLOS classes {@code geom.lisp} defines, in every spelling that names one.
	 * A form may reach a geom class without calling a geom function -- a
	 * {@code defmethod} specializer, a {@code typep}, a {@code typecase} clause, a
	 * {@code make-instance}, a {@code defclass} superclass -- and the interpreter's lazy
	 * load is keyed on FUNCTION resolution, so those forms need a trigger of their own
	 * ({@link #mentionsGeomClass}). The compile path needs none: its splice fires on any
	 * {@code geom:} symbol anywhere.
	 */
	private static final List<String> CLASS_NAMES = List.of(LispNames.GEOM_PKG + ":TRANSFORM",
			LispNames.GEOM_PKG + ":NODE", LispNames.GEOM_PKG + ":SOLID", LispNames.GEOM_PKG + ":BOUNDS",
			LispNames.GEOM_PKG + "::TRANSFORM", LispNames.GEOM_PKG + "::NODE", LispNames.GEOM_PKG + "::SOLID",
			LispNames.GEOM_PKG + "::BOUNDS");

	/**
	 * Whether {@code form} mentions one of the {@code geom} class names anywhere -- the
	 * trigger for the interpreter to load the library before a {@code defmethod}
	 * specializer / {@code typep} / {@code typecase} / {@code make-instance} /
	 * {@code defclass} superclass resolves one.
	 * @param form the form about to be evaluated
	 * @return {@code true} when a geom class name occurs anywhere in it
	 */
	public static boolean mentionsGeomClass(LispVal form) {
		if (form instanceof LispSymbol sym) {
			return CLASS_NAMES.contains(sym.name());
		}
		if (form instanceof LispCons cons) {
			return mentionsGeomClass(cons.car()) || mentionsGeomClass(cons.cdr());
		}
		return false;
	}

	/**
	 * The compile-path pre-pass: when the program references the {@code geom} package (a
	 * {@code geom:}/{@code geom::} qualified symbol anywhere, or a bare exported name
	 * while {@code (in-package geom)} is in effect), prepends the library definitions. A
	 * program that does not use geom is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the geom library spliced in when used
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
					if (isGeomQualified(sym.name()) || (LispNames.GEOM_PKG.equals(this.currentPackage)
							&& PackageRegistry.geomFunctionNames().contains(sym.name().toUpperCase(Locale.ROOT)))) {
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
