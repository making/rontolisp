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
 * {@code objc-native-primitives.lisp}, the {@code objc} primitive layer of a
 * {@code --native} output: it defines every primitive the interpreter binds, and the
 * compile path splices it exactly into a native program that reaches a macOS package.
 * What it DOES is pinned by {@code e2e/NativeObjcE2eTest} against the interpreter.
 */
class ObjcNativeLibraryTest {

	@Test
	void theLibraryDefinesEveryPrimitiveAndTheSleepTheBackendRoutesTo() {
		// What objc.lisp is written over (the imports define the rest), on-main, and the
		// sleep every sleep of such a program compiles to -- and no exported name but
		// on-main: the vocabulary is objc.lisp's.
		List<String> primitives = new ArrayList<>(defined(ObjcNativeLibrary.forms()));
		primitives.addAll(imported(ObjcNativeLibrary.forms()));
		assertThat(primitives).contains(LispNames.OBJC_SLEEP_INTERNAL, "OBJC:" + LispNames.OBJC_ON_MAIN)
			.containsAll(LispNames.OBJC_PRIMITIVES);
		assertThat(primitives.stream().filter(n -> n.startsWith("OBJC:") && !n.startsWith("OBJC::")))
			.containsExactly("OBJC:" + LispNames.OBJC_ON_MAIN);
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
		List<LispVal> objc = read("(objc:invoke \"NSString\" \"string\")");
		assertThat(ObjcNativeLibrary.process(objc, false)).isSameAs(objc);
		int spliced = ObjcNativeLibrary.forms().size();
		assertThat(ObjcNativeLibrary.process(objc, true)).hasSize(spliced + 1).endsWith(objc.getFirst());
		// An appkit: program reaches it too: the widget layer is written over objc:.
		assertThat(ObjcNativeLibrary.process(read("(in-package appkit) (window \"t\")"), true)).hasSize(spliced + 2);
	}

	private static List<LispVal> read(String source) {
		return LispReader.readAllFromString(source);
	}

}
