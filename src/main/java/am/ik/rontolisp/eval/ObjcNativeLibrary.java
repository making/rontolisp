package am.ik.rontolisp.eval;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.Features;
import am.ik.rontolisp.reader.LispReader;
import org.jspecify.annotations.Nullable;

/**
 * The {@code objc:} verbs of a {@code --native} output: {@code objc-native.lisp}, written
 * in rontolisp over the {@code rlobjc} imports the runner stub answers on macOS
 * ({@code rontolisp-native/runner/src/objc}), and in front of it the new base's primitive
 * layer ({@code objc-native-primitives.lisp}, the {@code p_*} imports) that
 * {@code objc.lisp} is written over. The compile path splices it into a {@code --native}
 * program that references any of the four macOS packages, AFTER {@link AppKitLibrary}
 * (whose widget layer is what introduces the {@code objc:} references of an
 * {@code appkit:} program). A {@code .wasm} output still refuses such a program: only the
 * runner provides the imports. See {@code .kb/objc.md}, "--native".
 */
public final class ObjcNativeLibrary {

	private static volatile @Nullable List<LispVal> forms;

	private static volatile @Nullable List<LispVal> primitiveForms;

	private ObjcNativeLibrary() {
	}

	/**
	 * Returns the parsed library definitions, parsed once and cached.
	 * @return the library forms
	 */
	public static List<LispVal> forms() {
		List<LispVal> cached = forms;
		if (cached == null) {
			synchronized (ObjcNativeLibrary.class) {
				cached = forms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource(), Features.INTERPRETER));
					forms = cached;
				}
			}
		}
		return cached;
	}

	/**
	 * Returns the new base's primitive layer on this target
	 * ({@code objc-native-primitives.lisp}), parsed once and cached.
	 * @return the forms
	 */
	public static List<LispVal> primitiveForms() {
		List<LispVal> cached = primitiveForms;
		if (cached == null) {
			synchronized (ObjcNativeLibrary.class) {
				cached = primitiveForms;
				if (cached == null) {
					cached = List.copyOf(LispReader.readAllFromString(readSource("objc-native-primitives.lisp"),
							Features.INTERPRETER));
					primitiveForms = cached;
				}
			}
		}
		return cached;
	}

	private static String readSource() {
		return readSource("objc-native.lisp");
	}

	private static String readSource(String name) {
		try (InputStream in = ObjcNativeLibrary.class.getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException(name + " is missing from the classpath");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * The compile-path pre-pass: prepends the library when the program references a macOS
	 * package ({@link AppKitLibrary#firstObjcReference}) and the output is a native
	 * executable; returns the program unchanged otherwise.
	 * @param program the top-level forms, after the macOS library splices
	 * @param nativeOutput whether the output is a {@code --native} executable
	 * @return the program, with the library spliced in when needed
	 */
	public static List<LispVal> process(List<LispVal> program, boolean nativeOutput) {
		if (!nativeOutput || AppKitLibrary.firstObjcReference(program) == null) {
			return program;
		}
		// The new base's primitives first: they define objc:on-main and objc::%sleep,
		// which both bases use.
		List<LispVal> out = new ArrayList<>(primitiveForms());
		out.addAll(forms());
		out.addAll(program);
		return out;
	}

}
