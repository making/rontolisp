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
				.isEqualTo("a package that uses objc or cocoa");
		}
		assertThat(ObjcLibrary.references(read("(defpackage :app (:use :cl)) (invoke 1 2)"))).isFalse();
	}

}
