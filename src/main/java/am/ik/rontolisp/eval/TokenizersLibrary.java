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
 * The {@code tokenizer} package: the two BPE tokenizers a published language model ships
 * with -- GPT-2-style BYTE-LEVEL BPE ({@code tokenizer:make-bpe}: SmolLM2, Qwen 2.5 / 3 /
 * 3.5, Llama 3, LFM2.5) and the SentencePiece-style BPE with per-piece scores
 * ({@code tokenizer:make-sentencepiece}: Llama 2, TinyLlama) -- behind one
 * {@code tokenizer:encode} / {@code tokenizer:decode}, written in rontolisp itself
 * ({@code tokenizers.lisp} on the classpath) over nothing but {@code cl}.
 *
 * <p>
 * Like {@link GeomLibrary} and unlike {@link AppKitLibrary} this one is
 * backend-INDEPENDENT, and more strictly so: it reaches for no {@code objc:}, no
 * {@code java:}, no {@code linalg:} and no FILESYSTEM. The vocabulary is an argument, not
 * a file the library opens (a GGUF or {@code tokenizer.json} reader supplies it, or a
 * test fixture does), so the same definitions run in the browser playground and compile
 * to both WASM backends ({@code .kb/tokenizers.md}).
 *
 * <p>
 * Consumers, the {@link GeomLibrary} pair:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time a {@code tokenizer:}-qualified function is resolved
 * ({@code LispEvaluator#resolveFunction});</li>
 * <li>the compile path ({@code CompileFrontend}, the web playground and tests that drive
 * the compilers directly) calls {@link #process(List)}, which prepends the definitions
 * when the program references the package. It has no place in the splice ORDER, because
 * it neither references another library nor is referenced by one.</li>
 * </ul>
 */
public final class TokenizersLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private TokenizersLibrary() {
	}

	/**
	 * Returns the parsed library definitions. Written in canonical shape (external
	 * single-colon public names, internal {@code tokenizer::%} helpers, bare {@code cl}
	 * names), so it needs no package resolution and re-resolving it is a no-op. Parsed
	 * once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (TokenizersLibrary.class) {
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
		try (InputStream in = TokenizersLibrary.class.getResourceAsStream("tokenizers.lisp")) {
			if (in == null) {
				throw new IllegalStateException("tokenizers.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the given symbol name references the {@code tokenizer} package: any
	 * {@code tokenizer:}/{@code tokenizer::} qualified name.
	 * @param symbolName the symbol name as written
	 * @return {@code true} when the name is tokenizer-qualified
	 */
	public static boolean isTokenizerQualified(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.TOKENIZER_PKG.equals(qn.pkg());
	}

	/**
	 * The compile-path pre-pass: when the program references the {@code tokenizer}
	 * package (a {@code tokenizer:}/{@code tokenizer::} qualified symbol anywhere, or a
	 * bare exported name while {@code (in-package tokenizer)} is in effect), prepends the
	 * library definitions. A program that does not use it is returned unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the tokenizer library spliced in when used
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
					if (isTokenizerQualified(sym.name()) || (LispNames.TOKENIZER_PKG.equals(this.currentPackage)
							&& PackageRegistry.tokenizerFunctionNames()
								.contains(sym.name().toUpperCase(Locale.ROOT)))) {
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
