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
	void clUserQualifiedSymbolNormalizesToBare() {
		assertThat(resolve("cl-user:foo")).isEqualTo("foo");
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
	void newPackageInRegistryResolvesViaUseListAndQualifiesOwnSymbols() {
		// Extensibility: registering a package that uses rontolisp makes its symbols
		// (version) visible unqualified, and the resolution logic is unchanged.
		PackageRegistry registry = new PackageRegistry();
		registry.define(new LispPackage("mypkg", List.of(LispNames.RONTOLISP_PKG), Set.of("greet")));
		PackageResolver resolver = new PackageResolver(registry);
		resolve(resolver, "(in-package mypkg)");
		assertThat(resolve(resolver, "(version)")).isEqualTo("(rontolisp:version)");
		assertThat(resolve(resolver, "(greet)")).isEqualTo("(mypkg:greet)");
		assertThat(resolve(resolver, "(cl:car x)")).isEqualTo("(car mypkg:x)");
	}

}
