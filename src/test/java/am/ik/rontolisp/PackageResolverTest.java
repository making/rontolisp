package am.ik.rontolisp;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.reader.LispReader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackageResolverTest {

	private static String resolve(PackageResolver resolver, String src) {
		return resolver.resolve(LispReader.readFromString(src)).print();
	}

	private static String resolve(String src) {
		return resolve(new PackageResolver(), src);
	}

	@Test
	void clQualifiedSymbolNormalizesToBare() {
		assertThat(resolve("(cl:car x)")).isEqualTo("(car x)");
	}

	@Test
	void clUserSingleColonIsRejectedBecauseNothingIsExported() {
		// cl-user exports nothing, like the Common Lisp COMMON-LISP-USER package.
		assertThatThrownBy(() -> resolve("cl-user:foo")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("The symbol foo is not external in the cl-user package")
			.hasMessageContaining("use cl-user::foo");
	}

	@Test
	void clUserDoubleColonNormalizesToBare() {
		assertThat(resolve("cl-user::foo")).isEqualTo("foo");
	}

	@Test
	void clDoubleColonNormalizesToBare() {
		assertThat(resolve("(cl::car x)")).isEqualTo("(car x)");
	}

	@Test
	void clCarCdrCompositionIsExternal() {
		assertThat(resolve("(cl:cadr x)")).isEqualTo("(cadr x)");
	}

	@Test
	void clInternalHelperRequiresDoubleColon() {
		assertThatThrownBy(() -> resolve("(cl:%remf-tail x y)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("The symbol %remf-tail is not external in the cl package")
			.hasMessageContaining("use cl::%remf-tail");
		assertThat(resolve("(cl::%remf-tail x y)")).isEqualTo("(%remf-tail x y)");
	}

	@Test
	void rontolispInternalSingleColonIsRejected() {
		assertThatThrownBy(() -> resolve("(rontolisp:%json-parse s nil)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("The symbol %json-parse is not external in the rontolisp package")
			.hasMessageContaining("use rontolisp::%json-parse");
	}

	@Test
	void rontolispInternalDoubleColonIsKept() {
		assertThat(resolve("(rontolisp::%json-parse s nil)")).isEqualTo("(rontolisp::%json-parse s nil)");
	}

	@Test
	void rontolispExternalDoubleColonNormalizesToSingleColon() {
		assertThat(resolve("(rontolisp::version)")).isEqualTo("(rontolisp:version)");
	}

	@Test
	void unqualifiedInternalInRontolispCanonicalizesToDoubleColon() {
		// An unregistered (user-defined) symbol is internal to its package, so the
		// canonical spelling uses the double colon.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(in-package :rontolisp)");
		assertThat(resolve(resolver, "(%my-helper x)")).isEqualTo("(rontolisp::%my-helper rontolisp::x)");
	}

	@Test
	void clVisibleSymbolsStayBareInClUser() {
		assertThat(resolve("(if a b c)")).isEqualTo("(if a b c)");
	}

	@Test
	void unqualifiedVersionInRontolispBecomesQualified() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(in-package :rontolisp)");
		assertThat(resolve(resolver, "(version)")).isEqualTo("(rontolisp:version)");
	}

	@Test
	void rontolispVersionQualifiedIsKept() {
		assertThat(resolve("(rontolisp:version)")).isEqualTo("(rontolisp:version)");
	}

	@Test
	void wasmExportQualifiedIsKeptWithQuotedDatumAndKeywords() {
		assertThat(resolve("(rontolisp:wasm-export 'fact :params '(:int) :returns :int)"))
			.isEqualTo("(rontolisp:wasm-export (quote fact) :params (quote (:int)) :returns :int)");
	}

	@Test
	void packageVarExpandsToQuotedCurrentPackage() {
		assertThat(resolve("*package*")).isEqualTo("(quote cl-user)");
	}

	@Test
	void inPackageAcceptsKeywordAndBareSymbol() {
		assertThat(resolve("(in-package :rontolisp)")).isEqualTo("(quote rontolisp)");
		assertThat(resolve("(in-package rontolisp)")).isEqualTo("(quote rontolisp)");
	}

	@Test
	void quoteDatumIsLeftUntouched() {
		assertThat(resolve("'(car x if)")).isEqualTo("(quote (car x if))");
	}

	@Test
	void quoteDatumUntouchedEvenInRontolisp() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(in-package :rontolisp)");
		assertThat(resolve(resolver, "'(car x)")).isEqualTo("(quote (car x))");
	}

	@Test
	void unqualifiedClSymbolInRontolispIsRejected() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(in-package :rontolisp)");
		assertThatThrownBy(() -> resolve(resolver, "(car x)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("use cl:car");
	}

	@Test
	void unqualifiedPackageVarInRontolispIsRejected() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(in-package :rontolisp)");
		assertThatThrownBy(() -> resolve(resolver, "*package*")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("use cl:*package*");
	}

	@Test
	void inPackageUnknownPackageIsRejected() {
		assertThatThrownBy(() -> resolve("(in-package foo)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("No such package: foo");
	}

	@Test
	void gensymStyleSymbolIsNotPackageQualified() {
		// A gensym-generated "#:g1" contains a colon but must not be parsed as the
		// package "#"; it stays a plain user symbol (expanded macro bodies reach the
		// resolver on the compile path).
		assertThat(resolve("(let ((#:g1 1)) #:g1)")).isEqualTo("(let ((#:g1 1)) #:g1)");
	}

	@Test
	void introspectionDesignatorIsNormalizedToKeyword() {
		assertThat(resolve("(rontolisp:list-functions cl)")).isEqualTo("(rontolisp:list-functions :cl)");
		assertThat(resolve("(rontolisp:list-functions :cl-user)")).isEqualTo("(rontolisp:list-functions :cl-user)");
		assertThat(resolve("(rontolisp:list-macros \"cl\")")).isEqualTo("(rontolisp:list-macros :cl)");
		assertThat(resolve("(rontolisp:list-special-forms 'rontolisp)"))
			.isEqualTo("(rontolisp:list-special-forms :rontolisp)");
	}

	@Test
	void introspectionWithoutArgumentIsKeptAsIs() {
		assertThat(resolve("(rontolisp:list-functions)")).isEqualTo("(rontolisp:list-functions)");
	}

	@Test
	void introspectionIsNormalizedInNestedForms() {
		assertThat(resolve("(print (rontolisp:list-macros cl))")).isEqualTo("(print (rontolisp:list-macros :cl))");
	}

	@Test
	void unqualifiedIntrospectionInRontolispBecomesQualified() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(in-package :rontolisp)");
		assertThat(resolve(resolver, "(list-functions)")).isEqualTo("(rontolisp:list-functions)");
	}

	@Test
	void introspectionUnknownPackageIsRejected() {
		assertThatThrownBy(() -> resolve("(rontolisp:list-functions :foo)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("No such package: foo");
	}

	@Test
	void introspectionWithTooManyArgumentsIsRejected() {
		assertThatThrownBy(() -> resolve("(rontolisp:list-functions :cl :cl-user)"))
			.isInstanceOf(LispPackageException.class)
			.hasMessageContaining("at most one package-designator argument");
	}

	@Test
	void introspectionWithNonLiteralDesignatorIsRejected() {
		assertThatThrownBy(() -> resolve("(rontolisp:list-functions (car x))")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("expects a package name");
	}

	@Test
	void unqualifiedListFunctionsInClUserStaysUserSymbol() {
		// list-functions is not a cl symbol; in cl-user it remains a bare user symbol.
		assertThat(resolve("(list-functions)")).isEqualTo("(list-functions)");
	}

	@Test
	void jsonLibraryFormsAreAResolverFixedPoint() {
		// JsonLibrary splices its forms into programs both before resolution (the
		// compile-path pre-pass) and after it (the interpreter's lazy load), which is
		// only sound while json.lisp and the wrappers are written in canonical shape:
		// resolving them must be a no-op.
		PackageResolver resolver = new PackageResolver();
		for (LispVal form : am.ik.rontolisp.eval.JsonLibrary.forms()) {
			assertThat(resolver.resolve(form).print()).isEqualTo(form.print());
		}
	}

	@Test
	void newPackageInRegistryResolvesViaUseListAndQualifiesOwnSymbols() {
		// Extensibility: registering a package that uses rontolisp makes its symbols
		// (version) visible unqualified, and the resolution logic is unchanged.
		PackageRegistry registry = new PackageRegistry();
		registry.define(new LispPackage("mypkg", List.of(LispNames.RONTOLISP_PKG), Set.of("greet")));
		PackageResolver resolver = new PackageResolver(registry);
		resolve(resolver, "(in-package mypkg)");
		assertThat(resolve(resolver, "(version)")).isEqualTo("(rontolisp:version)");
		assertThat(resolve(resolver, "(greet)")).isEqualTo("(mypkg:greet)");
		// x is not registered in mypkg, so it is internal: the canonical spelling is
		// mypkg::x.
		assertThat(resolve(resolver, "(cl:car x)")).isEqualTo("(car mypkg::x)");
	}

}
