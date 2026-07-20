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
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The {@code vec} package (portable packed-{@code f64} vector kernels), implemented once
 * in rontolisp itself ({@code vec.lisp} on the classpath) as the scalar reference /
 * cross-backend oracle. A vec vector is a rank-1 packed float array (single- or
 * double-float), so the definitions are plain {@code defun}s over {@code make-array}/
 * {@code aref} that run on the interpreter, the JVM compiler and the wasm-GC compiler,
 * exactly like {@link LinalgLibrary}.
 *
 * <p>
 * The {@code --no-gc} scalar WASM backend is the exception: it lowers the {@code vec:}
 * kernels to real fixed-width WASM SIMD itself ({@code NoGcWasmCompiler}) and has no
 * general array type, so it must NOT get this splice -- {@code RontoLispCli} gates
 * {@link #process(List)} off for {@code .wasm} + {@code --no-gc}.
 *
 * <p>
 * Consumers mirror {@link LinalgLibrary}: the interpreter lazily evaluates
 * {@link #forms()} on the first resolution of a {@code vec:}-qualified function; the JVM
 * / wasm-GC compile path calls {@link #process(List)} after user-macro expansion.
 */
public final class VecLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private VecLibrary() {
	}

	/**
	 * Returns the parsed library definitions (the {@code vec:} defuns and their
	 * {@code vec::%} helpers). Written in canonical shape (external single-colon public
	 * names, internal double-colon helpers, bare {@code cl} names), so it needs no
	 * package resolution and re-resolving it is a no-op. The width-preserving
	 * {@code vec::%make-like} allocator carries both a {@code single-float} and a
	 * {@code double-float} {@code make-array} branch (each a compile-time literal), so
	 * one set of forms produces {@code #f} on every backend -- there is no per-target
	 * variant. Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (VecLibrary.class) {
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
		try (InputStream in = VecLibrary.class.getResourceAsStream("vec.lisp")) {
			if (in == null) {
				throw new IllegalStateException("vec.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the given symbol name references the {@code vec} package: any
	 * {@code vec:}/{@code vec::} qualified name.
	 * @param symbolName the symbol name as written
	 * @return {@code true} when the name is vec-qualified
	 */
	public static boolean isVecQualified(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.VEC_PKG.equals(qn.pkg());
	}

	/**
	 * The compile-path pre-pass (JVM / wasm-GC): when the program references the
	 * {@code vec} package (a {@code vec:}/{@code vec::} qualified symbol anywhere, or a
	 * bare exported name while {@code (in-package vec)} is in effect), prepends the
	 * library definitions. A program that does not use vec is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the vec library spliced in when used
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
					if (isVecQualified(sym.name())
							|| (LispNames.VEC_PKG.equals(this.currentPackage) && PackageRegistry.vecFunctionNames()
								.contains(sym.name().toLowerCase(java.util.Locale.ROOT)))) {
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
