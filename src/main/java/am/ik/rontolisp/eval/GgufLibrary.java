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
 * The {@code gguf} package: reads a GGUF checkpoint -- the single file a downloaded small
 * language model most often IS, carrying the hyperparameters, the tokenizer and the
 * tensors together in the width the publisher chose. The header, the key/value block and
 * the tensor directory, then the tensors themselves as packed float arrays (F32 directly,
 * F16 / BF16 through {@code rontolisp:widen-float-bits}); a quantized tensor is refused
 * BY NAME when its body is asked for, so a file that carries one still opens, still lists
 * its directory and still hands over its tokenizer. Written in rontolisp itself
 * ({@code gguf.lisp} on the classpath) over nothing but {@code cl}.
 *
 * <p>
 * Like {@link GeomLibrary} and unlike {@link AppKitLibrary} this one is
 * backend-INDEPENDENT: it reaches for no {@code objc:}, no {@code java:} and no
 * {@code linalg:}. It DOES open a file -- that is what it is for -- which is the same
 * exception {@code geom}'s five model readers are, and it does so through ordinary ANSI
 * CL I/O that runs on all four backends. It cannot SEEK, because {@code file-position}
 * repositions nothing on any of them, so the tensor data is walked sequentially in
 * ascending offset order ({@code .kb/gguf.md}).
 *
 * <p>
 * Consumers, the {@link GeomLibrary} pair:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time a {@code gguf:}-qualified function is resolved
 * ({@code LispEvaluator#resolveFunction});</li>
 * <li>the compile path ({@code CompileFrontend}, the web playground and tests that drive
 * the compilers directly) calls {@link #process(List)}, which prepends the definitions
 * when the program references the package. It has no place in the splice ORDER, because
 * it neither references another library nor is referenced by one.</li>
 * </ul>
 */
public final class GgufLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private GgufLibrary() {
	}

	/**
	 * Returns the parsed library definitions. Written in canonical shape (external
	 * single-colon public names, internal {@code gguf::%} helpers, bare {@code cl}
	 * names), so it needs no package resolution and re-resolving it is a no-op. Parsed
	 * once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (GgufLibrary.class) {
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
		try (InputStream in = GgufLibrary.class.getResourceAsStream("gguf.lisp")) {
			if (in == null) {
				throw new IllegalStateException("gguf.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the given symbol name references the {@code gguf} package: any
	 * {@code gguf:}/{@code gguf::} qualified name.
	 * @param symbolName the symbol name as written
	 * @return {@code true} when the name is gguf-qualified
	 */
	public static boolean isGgufQualified(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.GGUF_PKG.equals(qn.pkg());
	}

	/**
	 * The compile-path pre-pass: when the program references the {@code gguf} package (a
	 * {@code gguf:}/{@code gguf::} qualified symbol anywhere, or a bare exported name
	 * while {@code (in-package gguf)} is in effect), prepends the library definitions. A
	 * program that does not use it is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the gguf library spliced in when used
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
					if (isGgufQualified(sym.name()) || (LispNames.GGUF_PKG.equals(this.currentPackage)
							&& PackageRegistry.ggufFunctionNames().contains(sym.name().toUpperCase(Locale.ROOT)))) {
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
