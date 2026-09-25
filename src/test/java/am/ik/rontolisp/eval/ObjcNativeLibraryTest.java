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
 * {@code objc-native.lisp}, the {@code objc:} verbs of a {@code --native} output: it
 * defines every verb the interpreter binds, and the compile path splices it exactly into
 * a native program that reaches a macOS package. What the verbs DO is pinned by
 * {@code e2e/NativeObjcE2eTest} against the interpreter.
 */
class ObjcNativeLibraryTest {

	@Test
	void theLibraryDefinesEveryObjcVerbAndTheSleepTheBackendRoutesTo() {
		List<String> defined = new ArrayList<>();
		for (LispVal form : ObjcNativeLibrary.forms()) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op && "DEFUN".equals(op.name())
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
				defined.add(name.name());
			}
		}
		assertThat(defined).contains(LispNames.OBJC_SLEEP_INTERNAL);
		assertThat(defined.stream().filter(n -> n.startsWith("OBJC:") && !n.startsWith("OBJC::")))
			.containsExactlyInAnyOrder("OBJC:" + LispNames.OBJC_CLASS, "OBJC:" + LispNames.OBJC_SEND,
					"OBJC:" + LispNames.OBJC_DEFINE_CLASS, "OBJC:" + LispNames.OBJC_ON_MAIN,
					"OBJC:" + LispNames.OBJC_STRING, "OBJC:" + LispNames.OBJC_DATA, "OBJC:" + LispNames.OBJC_BYTES,
					"OBJC:" + LispNames.OBJC_ADDRESS, "OBJC:" + LispNames.OBJC_OBJECTP);
	}

	@Test
	void theLibraryIsSplicedOnlyIntoANativeProgramThatReachesAMacOsPackage() {
		List<LispVal> plain = read("(print 1)");
		assertThat(ObjcNativeLibrary.process(plain, true)).isSameAs(plain);
		List<LispVal> objc = read("(objc:send \"NSString\" \"string\")");
		assertThat(ObjcNativeLibrary.process(objc, false)).isSameAs(objc);
		assertThat(ObjcNativeLibrary.process(objc, true)).hasSize(ObjcNativeLibrary.forms().size() + 1)
			.endsWith(objc.getFirst());
		// An appkit: program reaches it too: the widget layer is written over objc:.
		assertThat(ObjcNativeLibrary.process(read("(in-package appkit) (window \"t\")"), true))
			.hasSize(ObjcNativeLibrary.forms().size() + 2);
	}

	private static List<LispVal> read(String source) {
		return LispReader.readAllFromString(source);
	}

}
