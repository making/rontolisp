package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The {@code safetensors} package: {@code safetensors:read} (a Hugging Face
 * {@code model.safetensors}, a sharded {@code .index.json} or the directory holding
 * either, into a hash table of packed float arrays), {@code safetensors:header} and
 * {@code safetensors:entries} -- written in rontolisp itself ({@code safetensors.lisp} on
 * the classpath) over the {@link CheckpointLibrary} staging, {@code rontolisp:json-parse}
 * and the byte-stream primitives, so it runs on every backend that has a filesystem. The
 * format, the sequential walk (streams do not reposition) and the dtype set: the file's
 * header and {@code .kb/checkpoint-readers.md}.
 *
 * <p>
 * Consumers, the {@link GeomLibrary} pair:
 * <ul>
 * <li>the interpreter evaluates {@link #forms()} into the global environment the first
 * time a {@code safetensors:}-qualified function is resolved; the {@code checkpoint:}
 * calls in their bodies load that library through the same hook on first use;</li>
 * <li>the compile path ({@code CompileFrontend}) calls {@link #process(List)} BEFORE
 * {@code CheckpointLibrary.process} and {@code JsonLibrary.process}, so the references
 * inside the spliced definitions pull both in too.</li>
 * </ul>
 */
public final class SafetensorsLibrary {

	@Nullable private static volatile List<LispVal> forms;

	private SafetensorsLibrary() {
	}

	/**
	 * Returns the parsed library definitions, written in canonical shape and parsed once.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (SafetensorsLibrary.class) {
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
		try (InputStream in = SafetensorsLibrary.class.getResourceAsStream("safetensors.lisp")) {
			if (in == null) {
				throw new IllegalStateException("safetensors.lisp is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Returns whether the given symbol name references the {@code safetensors} package.
	 * @param symbolName the symbol name as written
	 * @return {@code true} when the name is safetensors-qualified
	 */
	public static boolean isSafetensorsQualified(String symbolName) {
		PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(symbolName);
		return qn != null && LispNames.SAFETENSORS_PKG.equals(qn.pkg());
	}

	/**
	 * The compile-path pre-pass: when the program references the {@code safetensors}
	 * package, prepends the library definitions; otherwise returns it unchanged.
	 * @param program the top-level forms (after load inlining and user-macro expansion)
	 * @return the program with the library spliced in when used
	 */
	public static List<LispVal> process(List<LispVal> program) {
		if (!CheckpointLibrary.references(program, LispNames.SAFETENSORS_PKG,
				PackageRegistry.safetensorsFunctionNames())) {
			return program;
		}
		List<LispVal> out = new ArrayList<>(forms());
		out.addAll(program);
		return out;
	}

}
