package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code objc-native.lisp}, the old {@code objc:} verbs of a {@code --native} output, and
 * {@code objc-native-primitives.lisp}, the new base's primitive layer in front of it:
 * together they define every name the interpreter binds, and the compile path splices
 * both exactly into a native program that reaches a macOS package. What they DO is pinned
 * by {@code e2e/NativeObjcE2eTest} against the interpreter.
 */
class ObjcNativeLibraryTest {

	@Test
	void theLibraryDefinesEveryObjcVerbAndTheSleepTheBackendRoutesTo() {
		List<String> old = defined(ObjcNativeLibrary.forms());
		assertThat(old.stream().filter(n -> n.startsWith("OBJC:") && !n.startsWith("OBJC::")))
			.containsExactlyInAnyOrder("OBJC:" + LispNames.OBJC_CLASS, "OBJC:" + LispNames.OBJC_SEND,
					"OBJC:" + LispNames.OBJC_DEFINE_CLASS, "OBJC:" + LispNames.OBJC_STRING,
					"OBJC:" + LispNames.OBJC_DATA, "OBJC:" + LispNames.OBJC_BYTES, "OBJC:" + LispNames.OBJC_ADDRESS,
					"OBJC:" + LispNames.OBJC_OBJECTP);
		// The primitive layer: what objc.lisp is written over (the imports define the
		// rest), on-main, and the sleep every sleep of such a program compiles to.
		List<String> primitives = new ArrayList<>(defined(ObjcNativeLibrary.primitiveForms()));
		primitives.addAll(imported(ObjcNativeLibrary.primitiveForms()));
		assertThat(primitives).contains(LispNames.OBJC_SLEEP_INTERNAL, "OBJC:" + LispNames.OBJC_ON_MAIN)
			.containsAll(LispNames.OBJC_PRIMITIVES);
		assertThat(old).doesNotContain(LispNames.OBJC_SLEEP_INTERNAL, "OBJC:" + LispNames.OBJC_ON_MAIN);
	}

	private static List<String> defined(List<LispVal> forms) {
		List<String> defined = new ArrayList<>();
		for (LispVal form : forms) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op && "DEFUN".equals(op.name())
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
				defined.add(name.name());
			}
		}
		return defined;
	}

	/** The names the file's rontolisp:wasm-import directives define. */
	private static List<String> imported(List<LispVal> forms) {
		List<String> imported = new ArrayList<>();
		for (LispVal form : forms) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op
					&& ("RONTOLISP:" + LispNames.WASM_IMPORT).equals(op.name()) && cons.cdr() instanceof LispCons rest
					&& rest.car() instanceof LispCons quoted && quoted.cdr() instanceof LispCons name
					&& name.car() instanceof LispSymbol symbol) {
				imported.add(symbol.name());
			}
		}
		return imported;
	}

	@Test
	void theLibraryIsSplicedOnlyIntoANativeProgramThatReachesAMacOsPackage() {
		List<LispVal> plain = read("(print 1)");
		assertThat(ObjcNativeLibrary.process(plain, true)).isSameAs(plain);
		List<LispVal> objc = read("(objc:send \"NSString\" \"string\")");
		assertThat(ObjcNativeLibrary.process(objc, false)).isSameAs(objc);
		int spliced = ObjcNativeLibrary.primitiveForms().size() + ObjcNativeLibrary.forms().size();
		assertThat(ObjcNativeLibrary.process(objc, true)).hasSize(spliced + 1).endsWith(objc.getFirst());
		// An appkit: program reaches it too: the widget layer is written over objc:.
		assertThat(ObjcNativeLibrary.process(read("(in-package appkit) (window \"t\")"), true)).hasSize(spliced + 2);
	}

	private static List<LispVal> read(String source) {
		return LispReader.readAllFromString(source);
	}

}
