package am.ik.rontolisp.eval;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.cli.RontoLispCli;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shipped {@code appkit} library as a library: it parses, it defines exactly the
 * functions the package registry exports, the interpreter loads it on the first
 * {@code appkit:} resolution, and the compile path refuses a program that reaches it.
 * Nothing here opens a window (CI has no display); the visible behavior is
 * {@code examples/macos/}.
 */
class AppKitLibraryTest {

	@Test
	void theLibraryDefinesEveryExportedFunctionAndNothingElseIsExported() {
		List<String> defined = new ArrayList<>();
		for (LispVal form : AppKitLibrary.forms()) {
			if (form instanceof LispCons cons && cons.car() instanceof LispSymbol op && "DEFUN".equals(op.name())
					&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name
					&& name.name().startsWith("APPKIT:") && !name.name().startsWith("APPKIT::")) {
				defined.add(name.name().substring("APPKIT:".length()));
			}
		}
		assertThat(defined).containsExactlyInAnyOrderElementsOf(PackageRegistry.appkitFunctionNames());
	}

	@Test
	void theLibraryLoadsOnTheFirstAppkitResolution() {
		// On a Mac the nil receiver answers nil; elsewhere the objc: verb underneath
		// signals -- either way the appkit: function was resolved and its body ran.
		String result = eval("""
				(defvar *before* (fboundp 'appkit:window))
				(defvar *first* (handler-case (appkit:visible-p nil) (error (e) (princ-to-string e))))
				(list *before* *first* (fboundp 'appkit:window) (fboundp 'appkit:button))
				""");
		assertThat(result).satisfiesAnyOf(r -> assertThat(r).isEqualTo("(NIL NIL T T)"),
				r -> assertThat(r).startsWith("(NIL \"objc:on-main: Objective-C is not available").endsWith(" T T)"));
	}

	@Test
	void qualifiedNamesAreRecognized() {
		assertThat(AppKitLibrary.isAppkitQualified("APPKIT:WINDOW")).isTrue();
		assertThat(AppKitLibrary.isAppkitQualified("APPKIT::%APP")).isTrue();
		assertThat(AppKitLibrary.isAppkitQualified("LINALG:DOT")).isFalse();
		assertThat(AppKitLibrary.isAppkitQualified("WINDOW")).isFalse();
	}

	@Test
	void theFirstInteropReferenceIsFoundQualifiedOrInPackage() {
		assertThat(AppKitLibrary.firstObjcReference(read("(print 1) (defun f () (objc:send x \"y\"))")))
			.isEqualTo("OBJC:SEND");
		assertThat(AppKitLibrary.firstObjcReference(read("(appkit:window \"t\")"))).isEqualTo("APPKIT:WINDOW");
		assertThat(AppKitLibrary.firstObjcReference(read("(in-package appkit) (window \"t\")")))
			.isEqualTo("APPKIT:WINDOW");
		assertThat(AppKitLibrary.firstObjcReference(read("(in-package objc) (send x \"y\")"))).isEqualTo("OBJC:SEND");
		assertThat(AppKitLibrary.firstObjcReference(read("(in-package cl-user) (defun send () 1) (print 'window)")))
			.isNull();
		assertThat(AppKitLibrary.firstObjcReference(read("(java:static \"java.lang.Math\" \"max\" 1 2)"))).isNull();
	}

	@Test
	void theCompilePathRefusesBothPackagesOnEveryBackend(@TempDir Path dir) throws IOException {
		Path source = dir.resolve("gui.lisp");
		Files.writeString(source, "(print (appkit:window \"hi\"))\n");
		Path loader = dir.resolve("loader.lisp");
		Files.writeString(loader, "(load \"gui.lisp\")\n");
		for (String[] output : new String[][] { { "-o", dir.resolve("Prog.class").toString() },
				{ "-o", dir.resolve("prog.wasm").toString() },
				{ "-o", dir.resolve("comp.wasm").toString(), "--component" } }) {
			assertThatThrownBy(() -> compile(source, output)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Cannot compile: APPKIT:WINDOW")
				.hasMessageContaining("interpreter only");
			// A (load ...)-ed file is caught too: the refusal runs after load inlining.
			assertThatThrownBy(() -> compile(loader, output)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Cannot compile: APPKIT:WINDOW");
		}
		Files.writeString(source, "(objc:send \"NSString\" \"stringWithUTF8String:\" \"x\")\n");
		assertThatThrownBy(() -> compile(source, "-o", dir.resolve("Prog.class").toString()))
			.hasMessageContaining("Cannot compile: OBJC:SEND");
	}

	private static void compile(Path source, String... options) {
		List<String> args = new ArrayList<>();
		args.add(source.toString());
		args.addAll(List.of(options));
		new RontoLispCli(new ByteArrayInputStream(new byte[0]), new PrintStream(new ByteArrayOutputStream()))
			.run(args.toArray(String[]::new));
	}

	private static List<LispVal> read(String source) {
		return LispReader.readAllFromString(source);
	}

	private static String eval(String input) {
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(new ByteArrayOutputStream()));
		LispVal result = LispNil.INSTANCE;
		for (LispVal expr : LispReader.readAllFromString(input)) {
			result = evaluator.eval(expr);
		}
		return result.print();
	}

}
