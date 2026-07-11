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
	void wasmImportQuotedNameResolvesInCurrentPackage() {
		// The quoted name of a wasm-import directive is a function name, not inert
		// data: inside a user package it must resolve like a defun name so call
		// sites (canonicalized to pkg:name) find the synthetic defun.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :gl (:use :cl) (:export :create-shader))");
		resolve(resolver, "(in-package :gl)");
		assertThat(resolve(resolver,
				"(rontolisp:wasm-import 'create-shader :from \"gl\" :as \"createShader\" :params '(:int) :returns :int)"))
			.isEqualTo("(rontolisp:wasm-import (quote gl:create-shader) :from \"gl\" :as \"createShader\""
					+ " :params (quote (:int)) :returns :int)");
		// An unexported name is internal to the package.
		assertThat(resolve(resolver, "(rontolisp:wasm-import 'fail :from \"ui\" :params '(:string) :returns :void)"))
			.isEqualTo(
					"(rontolisp:wasm-import (quote gl::fail) :from \"ui\" :params (quote (:string)) :returns :void)");
	}

	@Test
	void wasmImportQualifiedQuotedNameIsAFixedPoint() {
		// An explicitly qualified name (the pre-resolution workaround spelling)
		// re-resolves to itself, from any current package.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :gl (:use :cl) (:export :create-shader))");
		assertThat(resolve(resolver, "(rontolisp:wasm-import 'gl:create-shader :params '(:int) :returns :int)"))
			.isEqualTo("(rontolisp:wasm-import (quote gl:create-shader) :params (quote (:int)) :returns :int)");
	}

	@Test
	void wasmExportQuotedNameResolvesInCurrentPackage() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :gl (:use :cl) (:export :frame))");
		resolve(resolver, "(in-package :gl)");
		assertThat(resolve(resolver, "(rontolisp:wasm-export 'frame :as \"frame\" :params '(:float))"))
			.isEqualTo("(rontolisp:wasm-export (quote gl:frame) :as \"frame\" :params (quote (:float)))");
	}

	@Test
	void wasmExportQuotedSymbolAliasIsLeftUntouched() {
		// The :as value may leniently be a quoted symbol naming the WASM export
		// field; it is host-facing data, not a package-scoped symbol.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :gl (:use :cl) (:export :frame))");
		resolve(resolver, "(in-package :gl)");
		assertThat(resolve(resolver, "(rontolisp:wasm-export 'frame :as 'tick :params '(:float))"))
			.isEqualTo("(rontolisp:wasm-export (quote gl:frame) :as (quote tick) :params (quote (:float)))");
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
	void linalgLibraryFormsAreAResolverFixedPoint() {
		// LinalgLibrary splices its forms into programs both before resolution (the
		// compile-path pre-pass) and after it (the interpreter's lazy load), which is
		// only sound while linalg.lisp is written in canonical shape: resolving it
		// must be a no-op.
		PackageResolver resolver = new PackageResolver();
		for (LispVal form : am.ik.rontolisp.eval.LinalgLibrary.forms()) {
			assertThat(resolver.resolve(form).print()).isEqualTo(form.print());
		}
	}

	@Test
	void urlLibraryFormsAreAResolverFixedPoint() {
		// UrlLibrary splices its forms into programs both before resolution (the
		// compile-path pre-pass) and after it (the interpreter's lazy load), which is
		// only sound while url.lisp is written in canonical shape: resolving it must
		// be a no-op.
		PackageResolver resolver = new PackageResolver();
		for (LispVal form : am.ik.rontolisp.eval.UrlLibrary.forms()) {
			assertThat(resolver.resolve(form).print()).isEqualTo(form.print());
		}
	}

	@Test
	void usocketLibraryFormsAreAResolverFixedPoint() {
		// UsocketLibrary splices its forms into programs both before resolution (the
		// compile-path pre-pass and the LoadInliner built-in-system hook) and after it
		// (the interpreter's lazy load), which is only sound while usocket.lisp is
		// written in canonical shape: resolving it must be a no-op.
		PackageResolver resolver = new PackageResolver();
		for (LispVal form : am.ik.rontolisp.eval.UsocketLibrary.forms()) {
			assertThat(resolver.resolve(form).print()).isEqualTo(form.print());
		}
	}

	@Test
	void defpackageRegistersPackageAndResolvesQuoted() {
		PackageResolver resolver = new PackageResolver();
		assertThat(resolve(resolver, "(defpackage :mypkg (:use :cl) (:export :greet))")).isEqualTo("(quote mypkg)");
		// defpackage does not switch the current package.
		assertThat(resolve(resolver, "*package*")).isEqualTo("(quote cl-user)");
		assertThat(resolve(resolver, "(in-package :mypkg)")).isEqualTo("(quote mypkg)");
		assertThat(resolve(resolver, "*package*")).isEqualTo("(quote mypkg)");
	}

	@Test
	void defpackageExportedDefunIsExternalUnexportedIsInternal() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :mypkg (:use :cl) (:export :greet))");
		resolve(resolver, "(in-package :mypkg)");
		// The exported name is owned and external: the canonical spelling is
		// single-colon.
		assertThat(resolve(resolver, "(defun greet (x) (car x))"))
			.isEqualTo("(defun mypkg:greet (mypkg::x) (car mypkg::x))");
		// A name interned later (not in the :export clause) is internal.
		assertThat(resolve(resolver, "(defun helper (x) x)")).isEqualTo("(defun mypkg::helper (mypkg::x) mypkg::x)");
		resolve(resolver, "(in-package :cl-user)");
		assertThat(resolve(resolver, "(mypkg:greet '(1))")).isEqualTo("(mypkg:greet (quote (1)))");
		assertThat(resolve(resolver, "#'mypkg:greet")).isEqualTo("(function mypkg:greet)");
		assertThatThrownBy(() -> resolve(resolver, "(mypkg:helper 1)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("The symbol helper is not external in the mypkg package")
			.hasMessageContaining("use mypkg::helper");
		assertThat(resolve(resolver, "(mypkg::helper 1)")).isEqualTo("(mypkg::helper 1)");
	}

	@Test
	void defpackageWithoutUseClauseRequiresQualifiedCl() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :bare (:export :f))");
		resolve(resolver, "(in-package :bare)");
		assertThatThrownBy(() -> resolve(resolver, "(car x)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("use cl:car");
		assertThat(resolve(resolver, "(cl:car cl:*package*)")).isEqualTo("(car (quote bare))");
	}

	@Test
	void defpackageUseMakesOnlyExportedSymbolsVisible() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :base (:use :cl) (:export :pub))");
		resolve(resolver, "(in-package :base)");
		resolve(resolver, "(defun pub () 1)");
		resolve(resolver, "(defun priv () 2)");
		resolve(resolver, "(defpackage :client (:use :cl :base))");
		resolve(resolver, "(in-package :client)");
		// The exported symbol is inherited from the used package ...
		assertThat(resolve(resolver, "(pub)")).isEqualTo("(base:pub)");
		// ... but the internal one is not: an unqualified priv interns as client's own
		// symbol instead (CL: use makes only external symbols accessible).
		assertThat(resolve(resolver, "(priv)")).isEqualTo("(client::priv)");
		assertThat(resolve(resolver, "(base::priv)")).isEqualTo("(base::priv)");
	}

	@Test
	void defpackageAcceptsStringAndBareSymbolDesignators() {
		PackageResolver resolver = new PackageResolver();
		assertThat(resolve(resolver, "(defpackage \"strpkg\" (:use cl) (:export \"f\" g :h))"))
			.isEqualTo("(quote strpkg)");
		resolve(resolver, "(in-package :strpkg)");
		assertThat(resolve(resolver, "(defun f () 1)")).isEqualTo("(defun strpkg:f nil 1)");
		assertThat(resolve(resolver, "(defun g () 2)")).isEqualTo("(defun strpkg:g nil 2)");
		assertThat(resolve(resolver, "(defun h () 3)")).isEqualTo("(defun strpkg:h nil 3)");
	}

	@Test
	void defpackageIntrospectionDesignatorIsAccepted() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :mypkg (:use :cl))");
		assertThat(resolve(resolver, "(rontolisp:list-functions :mypkg)"))
			.isEqualTo("(rontolisp:list-functions :mypkg)");
	}

	@Test
	void defpackageExistingPackageIsRejected() {
		assertThatThrownBy(() -> resolve("(defpackage :cl-user)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("Package already exists: cl-user");
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :mypkg)");
		assertThatThrownBy(() -> resolve(resolver, "(defpackage :mypkg)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("Package already exists: mypkg");
	}

	@Test
	void defpackageUnknownUsedPackageIsRejected() {
		assertThatThrownBy(() -> resolve("(defpackage :mypkg (:use :nosuch))")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("No such package: nosuch");
	}

	@Test
	void defpackageUnsupportedClauseIsRejected() {
		assertThatThrownBy(() -> resolve("(defpackage :mypkg (:shadow :car))")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining(":shadow is not supported");
		assertThatThrownBy(() -> resolve("(defpackage :mypkg (:shadowing-import-from :other :f))"))
			.isInstanceOf(LispPackageException.class)
			.hasMessageContaining(":shadowing-import-from is not supported");
		assertThatThrownBy(() -> resolve("(defpackage :mypkg (:intern :f))")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("Unsupported defpackage clause: :intern");
	}

	@Test
	void defpackageDocumentationAndSizeAreIgnored() {
		PackageResolver resolver = new PackageResolver();
		assertThat(resolve(resolver, "(defpackage :mypkg (:use :cl) (:documentation \"doc\") (:size 10))"))
			.isEqualTo("(quote mypkg)");
	}

	@Test
	void defpackageNicknamesResolveLikeTheCanonicalName() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :mypackage (:use :cl) (:nicknames :mp :mypkg2) (:export :greet))");
		assertThat(resolve(resolver, "(mp:greet)")).isEqualTo("(mypackage:greet)");
		assertThat(resolve(resolver, "(mypkg2:greet)")).isEqualTo("(mypackage:greet)");
		assertThat(resolve(resolver, "(in-package :mp)")).isEqualTo("(quote mypackage)");
		assertThatThrownBy(() -> resolve(resolver, "(defpackage :mp)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("Package already exists: mp");
	}

	@Test
	void defpackageNicknameCollidingWithExistingPackageIsRejected() {
		assertThatThrownBy(() -> resolve("(defpackage :mypkg (:nicknames :cl-user))"))
			.isInstanceOf(LispPackageException.class)
			.hasMessageContaining("Package already exists: cl-user");
	}

	@Test
	void commonLispNicknamesResolveToClAndClUser() {
		PackageResolver resolver = new PackageResolver();
		assertThat(resolve(resolver, "(common-lisp:car x)")).isEqualTo("(car x)");
		assertThat(resolve(resolver, "(in-package :common-lisp-user)")).isEqualTo("(quote cl-user)");
		resolve(resolver, "(defpackage :mypkg (:use :common-lisp) (:export :f))");
		resolve(resolver, "(in-package :mypkg)");
		assertThat(resolve(resolver, "(car x)")).isEqualTo("(car mypkg::x)");
	}

	@Test
	void defpackageUninternedSymbolDesignatorsAreAccepted() {
		// The portable defpackage idiom spells every designator #:name.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage #:mypkg (:use #:common-lisp) (:nicknames #:mp) (:export #:greet))");
		resolve(resolver, "(in-package #:mypkg)");
		assertThat(resolve(resolver, "(defun greet () (car x))")).isEqualTo("(defun mypkg:greet nil (car mypkg::x))");
		assertThat(resolve(resolver, "(mp:greet)")).isEqualTo("(mypkg:greet)");
	}

	@Test
	void defpackageImportFromMapsToTheSourcePackage() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :base (:use :cl) (:export :pub))");
		resolve(resolver, "(defpackage :client (:use :cl) (:import-from :base :pub))");
		resolve(resolver, "(in-package :client)");
		// The imported name resolves unqualified to the source package's canonical
		// spelling, and client:pub redirects there too.
		assertThat(resolve(resolver, "(pub)")).isEqualTo("(base:pub)");
		assertThat(resolve(resolver, "(client::pub)")).isEqualTo("(base:pub)");
	}

	@Test
	void defpackageImportFromClWorksWithoutUsingCl() {
		// The (:import-from #:common-lisp ...) idiom of use-nothing libraries.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :bare (:import-from #:common-lisp #:car #:defun))");
		resolve(resolver, "(in-package :bare)");
		assertThat(resolve(resolver, "(car x)")).isEqualTo("(car bare::x)");
		assertThatThrownBy(() -> resolve(resolver, "(cdr x)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("Undefined symbol: cdr");
	}

	@Test
	void defpackageImportFromUnknownPackageIsRejected() {
		assertThatThrownBy(() -> resolve("(defpackage :mypkg (:import-from :nosuch :f))"))
			.isInstanceOf(LispPackageException.class)
			.hasMessageContaining("No such package: nosuch");
		assertThatThrownBy(() -> resolve("(defpackage :mypkg (:import-from))")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining(":import-from expects a package name");
	}

	@Test
	void uninternedSymbolPassesThroughUnresolved() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(in-package :rontolisp)");
		assertThat(resolve(resolver, "#:g1")).isEqualTo("#:g1");
	}

	@Test
	void defpackageMalformedClauseIsRejected() {
		assertThatThrownBy(() -> resolve("(defpackage :mypkg :use)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("expects (:use ...) / (:export ...) clauses");
		assertThatThrownBy(() -> resolve("(defpackage)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("defpackage expects a package name");
		assertThatThrownBy(() -> resolve("(defpackage (car x))")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("defpackage expects a package name");
	}

	@Test
	void defpackageNestedIsRejected() {
		assertThatThrownBy(() -> resolve("(print (defpackage :mypkg))")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("defpackage is only supported as a literal top-level form");
	}

	@Test
	void defpackageQuotedDatumIsLeftUntouched() {
		assertThat(resolve("'(defpackage :mypkg)")).isEqualTo("(quote (defpackage :mypkg))");
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
