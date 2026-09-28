package am.ik.rontolisp.eval;

import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code objc.lisp}, the new {@code objc} base and {@code cocoa}: which names load it on
 * the interpreter, and which programs the compile path splices it into. What it does is
 * pinned by {@code ObjcBaseTest} and the corpus it runs on every target.
 */
class ObjcLibraryTest {

	private static List<LispVal> read(String source) {
		return LispReader.readAllFromString(source);
	}

	@Test
	void theNamesThatLoadItAreTheNewBasesAndCocoas() {
		assertThat(ObjcLibrary.definesName("OBJC:INVOKE")).isTrue();
		assertThat(ObjcLibrary.definesName("OBJC:WITH-AUTORELEASE-POOL")).isTrue();
		assertThat(ObjcLibrary.definesName("COCOA:NS-NOT-FOUND")).isTrue();
		assertThat(ObjcLibrary.definesName("OBJC::%INVOKE")).isTrue();
		// The old verbs and the backend's primitives are not the library's.
		assertThat(ObjcLibrary.definesName("OBJC:SEND")).isFalse();
		assertThat(ObjcLibrary.definesName("OBJC:ON-MAIN")).isFalse();
		assertThat(ObjcLibrary.definesName("OBJC::%SEND")).isFalse();
		assertThat(ObjcLibrary.definesName("INVOKE")).isFalse();
		assertThat(ObjcLibrary.mentionsType(read("(typep x 'objc:objc-object-pointer)").getFirst())).isTrue();
		assertThat(ObjcLibrary.mentionsType(read("(typep x 'objc:object)").getFirst())).isFalse();
	}

	@Test
	void aProgramThatReferencesItGetsItSpliced() {
		List<LispVal> plain = read("(print 1)");
		assertThat(ObjcLibrary.process(plain)).isSameAs(plain);
		List<LispVal> old = read("(objc:send \"NSString\" \"string\")");
		assertThat(ObjcLibrary.process(old)).isSameAs(old);
		List<LispVal> invoking = read("(objc:invoke \"NSObject\" \"new\")");
		assertThat(ObjcLibrary.process(invoking)).hasSize(ObjcLibrary.forms().size() + 1).endsWith(invoking.getFirst());
		assertThat(ObjcLibrary.references(read("(cocoa:set-ns-range* (cons 0 0) 1 2)"))).isTrue();
		assertThat(ObjcLibrary.references(read("(in-package objc) (invoke \"NSObject\" \"new\")"))).isTrue();
	}

	@Test
	void aPackageThatUsesObjcReferencesItWithBareNames() {
		// The manual's examples assume the current package uses objc; on the compile path
		// a bare name is still bare when the library passes run.
		for (String form : List.of("(defpackage :app (:use :cl :objc))", "(defpackage #:app (:use #:cl #:objc))",
				"(defpackage \"APP\" (:use \"CL\" \"OBJC\"))", "(defpackage :app (:use :cl :cocoa))",
				"(use-package :objc)", "(use-package 'objc)")) {
			assertThat(ObjcLibrary.references(read(form + " (invoke \"NSObject\" \"new\")"))).as(form).isTrue();
			assertThat(AppKitLibrary.firstObjcReference(read(form))).as(form)
				.isEqualTo("a package that uses objc, cocoa or fli");
		}
		assertThat(ObjcLibrary.references(read("(defpackage :app (:use :cl)) (invoke 1 2)"))).isFalse();
	}

	@Test
	void theDefiningMacrosGoInFrontOfMacroExpansionAndTheClassHalfOnlyWhereUsed() {
		assertThat(ObjcLibrary.definesMacro("OBJC:DEFINE-OBJC-CLASS")).isTrue();
		assertThat(ObjcLibrary.definesMacro("OBJC:CURRENT-SUPER")).isTrue();
		assertThat(ObjcLibrary.definesMacro("OBJC:INVOKE")).isFalse();
		assertThat(ObjcLibrary.definesName("OBJC:DEFINE-OBJC-METHOD")).isTrue();
		assertThat(ObjcLibrary.definesName("OBJC::%DEFINE-OBJC-CLASS")).isTrue();
		assertThat(ObjcLibrary.mentionsType(read("(defclass a (objc:standard-objc-object) ())").getFirst())).isTrue();
		List<LispVal> plain = read("(print 1)");
		assertThat(ObjcLibrary.withMacros(plain)).isSameAs(plain);
		List<LispVal> defining = read("(objc:define-objc-class a () () (:objc-class-name \"A\"))");
		assertThat(ObjcLibrary.withMacros(defining)).hasSize(ObjcLibrary.macroForms().size() + 1);
		// After expansion: a program that defines gets the class half, one that only
		// calls carries none of it.
		List<LispVal> expanded = read("(objc::%define-objc-class 'a nil \"A\" nil nil nil)");
		assertThat(ObjcLibrary.process(expanded))
			.hasSize(ObjcLibrary.forms().size() + ObjcLibrary.classForms().size() + 1);
		List<LispVal> invoking = read("(objc:invoke \"NSObject\" \"new\")");
		assertThat(ObjcLibrary.referencesClassHalf(invoking)).isFalse();
		assertThat(ObjcLibrary.referencesClassHalf(read("(typep x 'objc:standard-objc-object)"))).isTrue();
	}

	@Test
	void theBlocksHalfIsSplicedOnlyWhereABlockIsNamed() {
		assertThat(ObjcLibrary.definesName("OBJC:MAKE-OBJC-BLOCK")).isTrue();
		assertThat(ObjcLibrary.definesMacro("OBJC:WITH-OBJC-BLOCK")).isTrue();
		assertThat(ObjcLibrary.definesMacro("OBJC:DEFINE-OBJC-BLOCK-TYPE")).isTrue();
		assertThat(ObjcLibrary.mentionsType(read("(typep x 'objc:objc-block)").getFirst())).isTrue();
		List<LispVal> invoking = read("(objc:invoke \"NSObject\" \"new\")");
		assertThat(ObjcLibrary.referencesBlockHalf(invoking)).isFalse();
		List<LispVal> blocking = read("(objc:make-objc-block '(:void ()) (lambda () nil))");
		assertThat(ObjcLibrary.process(blocking))
			.hasSize(ObjcLibrary.forms().size() + ObjcLibrary.blockForms().size() + 1);
		assertThat(ObjcLibrary.referencesBlockHalf(read("(typep x 'objc:objc-block)"))).isTrue();
	}

	@Test
	void fliIsTheLibrarysAndMacOsOnly() {
		// fli:define-foreign-function expands into a call of objc.lisp, and a package
		// that uses fli names it bare.
		assertThat(ObjcLibrary.definesName("FLI:DEFINE-FOREIGN-FUNCTION")).isTrue();
		assertThat(ObjcLibrary.definesMacro("FLI:DEFINE-FOREIGN-FUNCTION")).isTrue();
		List<LispVal> declaring = read("(fli:define-foreign-function (f \"f\") ())");
		assertThat(ObjcLibrary.withMacros(declaring)).hasSize(ObjcLibrary.macroForms().size() + 1);
		assertThat(ObjcLibrary.references(read("(in-package fli) (define-foreign-function (f \"f\") ())"))).isTrue();
		assertThat(AppKitLibrary.firstObjcReference(declaring)).isEqualTo("FLI:DEFINE-FOREIGN-FUNCTION");
		assertThat(AppKitLibrary.firstObjcReference(read("(defpackage :app (:use :cl :fli))")))
			.isEqualTo("a package that uses objc, cocoa or fli");
	}

}
