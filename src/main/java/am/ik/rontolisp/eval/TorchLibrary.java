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
 * The {@code torch} package (a PyTorch-style tensor with reverse-mode autograd over the
 * {@code linalg} kernels), implemented once in rontolisp itself ({@code torch.lisp} on
 * the classpath) so a single hand-written implementation runs on every backend. Like
 * {@link LinalgLibrary}, every public entry point is a plain {@code defun} (the one
 * macro, {@code torch:no-grad}, is a built-in {@code LispMacroExpander} expansion), so no
 * call-site rewriting is needed.
 *
 * <p>
 * Consumers:
 * <ul>
 * <li>the interpreter lazily evaluates {@link #forms()} into the global environment the
 * first time a {@code torch:}-qualified function is resolved (see
 * {@code LispEvaluator#resolveFunction}) or a {@code torch:no-grad} form is
 * evaluated;</li>
 * <li>the compile path ({@code RontoLispCli}, the web playground, and tests that drive
 * the compilers directly) calls {@link #process(List)} after user-macro expansion and
 * BEFORE {@code LinalgLibrary.process}, so the {@code linalg:} references inside the
 * spliced torch definitions pull the linalg library in too.</li>
 * </ul>
 */
public final class TorchLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private TorchLibrary() {
	}

	/**
	 * Returns the parsed library definitions (the {@code torch:} defuns, their
	 * {@code torch::%t-} helpers and the {@code torch::*grad-enabled*} mode variable).
	 * The source is written in canonical shape (external single-colon public names,
	 * internal double-colon helpers, bare {@code cl} names), so it needs no package
	 * resolution and re-resolving it is a no-op. Parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (TorchLibrary.class) {
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
		try (InputStream in = TorchLibrary.class.getResourceAsStream("torch.lisp")) {
			if (in == null) {
				throw new IllegalStateException("torch.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the given symbol name references the {@code torch} package: any
	 * {@code torch:}/{@code torch::} qualified name.
	 * @param symbolName the symbol name as written
	 * @return {@code true} when the name is torch-qualified
	 */
	public static boolean isTorchQualified(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.TORCH_PKG.equals(qn.pkg());
	}

	/**
	 * The compile-path pre-pass: when the program references the {@code torch} package (a
	 * {@code torch:}/{@code torch::} qualified symbol anywhere, or a bare exported name
	 * while {@code (in-package torch)} is in effect), prepends the library definitions. A
	 * program that does not use torch is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the torch library spliced in when used
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
					if (isTorchQualified(sym.name())
							|| (LispNames.TORCH_PKG.equals(this.currentPackage) && PackageRegistry.torchFunctionNames()
								.contains(sym.name().toUpperCase(java.util.Locale.ROOT)))) {
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
