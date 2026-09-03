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
 * The {@code checkpoint} package: staging a published model's tensors into packed float
 * arrays -- {@code checkpoint:make-tensor} (a packed destination, verified packed),
 * {@code checkpoint:stage-float-bits} (f16 / bf16 bit patterns read off a byte stream in
 * bounded chunks and widened through {@code rontolisp:widen-float-bits}),
 * {@code checkpoint:stage-float32} and {@code checkpoint:skip-bytes} -- written in
 * rontolisp itself ({@code checkpoint.lisp} on the classpath) and shared by every
 * checkpoint reader ({@link SafetensorsLibrary}, the GGUF reader). Why the staging is
 * chunked and why the destination is checked: the file's header and
 * {@code .kb/checkpoint-readers.md}.
 *
 * <p>
 * Consumers, the {@link GeomLibrary} pair:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time a {@code checkpoint:}-qualified function is resolved
 * ({@code LispEvaluator#resolveFunction});</li>
 * <li>the compile path ({@code CompileFrontend}) calls {@link #process(List)} AFTER
 * {@code SafetensorsLibrary.process}, so the {@code checkpoint:} references inside a
 * spliced reader pull this library in too, and BEFORE the prelude, which supplies
 * {@code rontolisp:widen-float-bits}' wrapper.</li>
 * </ul>
 */
public final class CheckpointLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private CheckpointLibrary() {
	}

	/**
	 * Returns the parsed library definitions. Written in canonical shape (external
	 * single-colon public names, internal {@code checkpoint::%} helpers, bare {@code cl}
	 * names), so it needs no package resolution. Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (CheckpointLibrary.class) {
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
		try (InputStream in = CheckpointLibrary.class.getResourceAsStream("checkpoint.lisp")) {
			if (in == null) {
				throw new IllegalStateException("checkpoint.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the given symbol name references the {@code checkpoint} package:
	 * any {@code checkpoint:}/{@code checkpoint::} qualified name.
	 * @param symbolName the symbol name as written
	 * @return {@code true} when the name is checkpoint-qualified
	 */
	public static boolean isCheckpointQualified(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.CHECKPOINT_PKG.equals(qn.pkg());
	}

	/**
	 * The compile-path pre-pass: when the program references the {@code checkpoint}
	 * package (a qualified symbol anywhere, or a bare exported name while
	 * {@code (in-package checkpoint)} is in effect), prepends the library definitions. A
	 * program that does not use it is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		if (!references(program, LispNames.CHECKPOINT_PKG, PackageRegistry.checkpointFunctionNames())) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(forms());
		out.addAll(program);
		return out;
	}

	/**
	 * Whether {@code program} references package {@code pkg}: a {@code pkg:}-qualified
	 * symbol anywhere, or one of {@code exportedNames} bare while {@code (in-package
	 * pkg)} is in effect. Shared with {@link SafetensorsLibrary}.
	 * @param program the top-level forms
	 * @param pkg the canonical package name
	 * @param exportedNames the package's exported names, upper-case
	 * @return {@code true} when the program reaches the package
	 */
	static boolean references(List<LispVal> program, String pkg, List<String> exportedNames) {
		Walker walker = new Walker(pkg, exportedNames);
		for (LispVal form : program) {
			walker.trackTopLevelInPackage(form);
			walker.detect(form);
		}
		return walker.found;
	}

	private static final class Walker {

		private final String pkg;

		private final List<String> exportedNames;

		private boolean found;

		private String currentPackage = LispNames.CL_USER_PKG;

		Walker(String pkg, List<String> exportedNames) {
			this.pkg = pkg;
			this.exportedNames = exportedNames;
		}

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
					PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
					boolean qualified = qn != null && this.pkg.equals(qn.pkg());
					if (qualified || (this.pkg.equals(this.currentPackage)
							&& this.exportedNames.contains(sym.name().toUpperCase(Locale.ROOT)))) {
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
