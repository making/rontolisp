package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The {@code simd} package (portable packed-{@code f64} vector kernels), implemented once
 * in rontolisp itself ({@code simd.lisp} on the classpath) as the scalar reference /
 * cross-backend oracle. A simd vector is a rank-1 array of doubles, so the definitions
 * are plain {@code defun}s over {@code make-array}/{@code aref} that run on every backend
 * WITH a general array type (the interpreter, the JVM compiler and the wasm-GC compiler),
 * exactly like {@link LinalgLibrary}.
 *
 * <p>
 * The {@code --no-gc} scalar WASM backend is the exception: it lowers the {@code simd:}
 * kernels to real fixed-width WASM SIMD itself ({@code ScalarWasmCompiler}) and has no
 * general array type, so it must NOT get this splice -- {@code RontoLispCli} gates
 * {@link #process(List)} off for {@code .wasm} + {@code --no-gc}.
 *
 * <p>
 * Consumers mirror {@link LinalgLibrary}: the interpreter lazily evaluates
 * {@link #forms()} on the first resolution of a {@code simd:}-qualified function; the JVM
 * / wasm-GC compile path calls {@link #process(List)} after user-macro expansion.
 */
public final class SimdLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private SimdLibrary() {
	}

	/**
	 * Returns the parsed library definitions (the {@code simd:} defuns and their
	 * {@code simd::%} helpers). Written in canonical shape (external single-colon public
	 * names, internal double-colon helpers, bare {@code cl} names), so it needs no
	 * package resolution. Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (SimdLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = LispReader.readAllFromString(readSource());
					forms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		try (InputStream in = SimdLibrary.class.getResourceAsStream("simd.lisp")) {
			if (in == null) {
				throw new IllegalStateException("simd.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the given symbol name references the {@code simd} package: any
	 * {@code simd:}/{@code simd::} qualified name.
	 * @param symbolName the symbol name as written
	 * @return {@code true} when the name is simd-qualified
	 */
	public static boolean isSimdQualified(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.SIMD_PKG.equals(qn.pkg());
	}

	/**
	 * The compile-path pre-pass (JVM / wasm-GC): when the program references the
	 * {@code simd} package (a {@code simd:}/{@code simd::} qualified symbol anywhere, or
	 * a bare exported name while {@code (in-package simd)} is in effect), prepends the
	 * library definitions. A program that does not use simd is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the simd library spliced in when used
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
				this.currentPackage = name;
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
					if (isSimdQualified(sym.name()) || (LispNames.SIMD_PKG.equals(this.currentPackage)
							&& PackageRegistry.simdFunctionNames().contains(sym.name()))) {
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
