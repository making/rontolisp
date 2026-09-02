package am.ik.rontolisp;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.reader.Features;
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
	void pipeAndBackslashEscapedSymbolsSurviveCanonicalization() {
		// postmodern exports symbol names starting with an escaped "!" and s-sql
		// dispatches on pipe-escaped keywords, including the empty-name one. The
		// resolver must leave every escaped spelling alone: an escaped character keeps
		// its case and is never treated as a package marker.
		assertThat(resolvedSymbol(":|a b|").name()).isEqualTo(":a b");
		assertThat(resolvedSymbol("|foo bar|").name()).isEqualTo("foo bar");
		assertThat(resolvedSymbol(":||").name()).isEqualTo(":");
		assertThat(resolvedSymbol(":|\\||").name()).isEqualTo(":|");
		assertThat(resolvedSymbol("\\!dao-def").name()).isEqualTo("!DAO-DEF");
		assertThat(resolvedSymbol("\\!index").name()).isEqualTo("!INDEX");
		// and a defun of such a name resolves its head the same way
		assertThat(resolve("(defun \\!index (x) x)")).isEqualTo("(DEFUN !INDEX (X) X)");
	}

	private static LispSymbol resolvedSymbol(String src) {
		return (LispSymbol) new PackageResolver().resolve(LispReader.readFromString(src));
	}

	@Test
	void clQualifiedSymbolNormalizesToBare() {
		assertThat(resolve("(cl:car x)")).isEqualTo("(CAR X)");
	}

	@Test
	void clUserSingleColonIsRejectedBecauseNothingIsExported() {
		// cl-user exports nothing, like the Common Lisp COMMON-LISP-USER package.
		assertThatThrownBy(() -> resolve("cl-user:foo")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("The symbol FOO is not external in the CL-USER package")
			.hasMessageContaining("use CL-USER::FOO");
	}

	@Test
	void clUserDoubleColonNormalizesToBare() {
		assertThat(resolve("cl-user::foo")).isEqualTo("FOO");
	}

	@Test
	void clDoubleColonNormalizesToBare() {
		assertThat(resolve("(cl::car x)")).isEqualTo("(CAR X)");
	}

	@Test
	void clCarCdrCompositionIsExternal() {
		assertThat(resolve("(cl:cadr x)")).isEqualTo("(CADR X)");
	}

	@Test
	void clInternalHelperRequiresDoubleColon() {
		assertThatThrownBy(() -> resolve("(cl:%remf-tail x y)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("The symbol %REMF-TAIL is not external in the CL package")
			.hasMessageContaining("use CL::%REMF-TAIL");
		assertThat(resolve("(cl::%remf-tail x y)")).isEqualTo("(%REMF-TAIL X Y)");
	}

	@Test
	void rontolispInternalSingleColonIsRejected() {
		assertThatThrownBy(() -> resolve("(rontolisp:%json-parse s nil)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("The symbol %JSON-PARSE is not external in the RONTOLISP package")
			.hasMessageContaining("use RONTOLISP::%JSON-PARSE");
	}

	@Test
	void rontolispInternalDoubleColonIsKept() {
		assertThat(resolve("(rontolisp::%json-parse s nil)")).isEqualTo("(RONTOLISP::%JSON-PARSE S NIL)");
	}

	@Test
	void rontolispExternalDoubleColonNormalizesToSingleColon() {
		assertThat(resolve("(rontolisp::version)")).isEqualTo("(RONTOLISP:VERSION)");
	}

	@Test
	void unqualifiedInternalInRontolispCanonicalizesToDoubleColon() {
		// An unregistered (user-defined) symbol is internal to its package, so the
		// canonical spelling uses the double colon.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(in-package :rontolisp)");
		assertThat(resolve(resolver, "(%my-helper x)")).isEqualTo("(RONTOLISP::%MY-HELPER RONTOLISP::X)");
	}

	@Test
	void clVisibleSymbolsStayBareInClUser() {
		assertThat(resolve("(if a b c)")).isEqualTo("(IF A B C)");
	}

	@Test
	void unqualifiedVersionInRontolispBecomesQualified() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(in-package :rontolisp)");
		assertThat(resolve(resolver, "(version)")).isEqualTo("(RONTOLISP:VERSION)");
	}

	@Test
	void rontolispVersionQualifiedIsKept() {
		assertThat(resolve("(RONTOLISP:VERSION)")).isEqualTo("(RONTOLISP:VERSION)");
	}

	@Test
	void wasmExportQualifiedIsKeptWithQuotedDatumAndKeywords() {
		assertThat(resolve("(rontolisp:wasm-export 'fact :params '(:int) :returns :int)"))
			.isEqualTo("(RONTOLISP:WASM-EXPORT 'FACT :PARAMS '(:INT) :RETURNS :INT)");
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
				"(RONTOLISP:WASM-IMPORT 'create-shader :from \"gl\" :as \"createShader\" :params '(:int) :returns :int)"))
			.isEqualTo("(RONTOLISP:WASM-IMPORT 'GL:CREATE-SHADER :FROM \"gl\" :AS \"createShader\""
					+ " :PARAMS '(:INT) :RETURNS :INT)");
		// An unexported name is internal to the package.
		assertThat(resolve(resolver, "(RONTOLISP:WASM-IMPORT 'fail :from \"ui\" :params '(:string) :returns :void)"))
			.isEqualTo("(RONTOLISP:WASM-IMPORT 'GL::FAIL :FROM \"ui\" :PARAMS '(:STRING) :RETURNS :VOID)");
	}

	@Test
	void wasmImportQualifiedQuotedNameIsAFixedPoint() {
		// An explicitly qualified name (the pre-resolution workaround spelling)
		// re-resolves to itself, from any current package.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :gl (:use :cl) (:export :create-shader))");
		assertThat(resolve(resolver, "(rontolisp:wasm-import 'gl:create-shader :params '(:int) :returns :int)"))
			.isEqualTo("(RONTOLISP:WASM-IMPORT 'GL:CREATE-SHADER :PARAMS '(:INT) :RETURNS :INT)");
	}

	@Test
	void wasmExportQuotedNameResolvesInCurrentPackage() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :gl (:use :cl) (:export :frame))");
		resolve(resolver, "(in-package :gl)");
		assertThat(resolve(resolver, "(rontolisp:wasm-export 'frame :as \"frame\" :params '(:float))"))
			.isEqualTo("(RONTOLISP:WASM-EXPORT 'GL:FRAME :AS \"frame\" :PARAMS '(:FLOAT))");
	}

	@Test
	void wasmExportQuotedSymbolAliasIsLeftUntouched() {
		// The :as value may leniently be a quoted symbol naming the WASM export
		// field; it is host-facing data, not a package-scoped symbol.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :gl (:use :cl) (:export :frame))");
		resolve(resolver, "(in-package :gl)");
		assertThat(resolve(resolver, "(rontolisp:wasm-export 'frame :as 'tick :params '(:float))"))
			.isEqualTo("(RONTOLISP:WASM-EXPORT 'GL:FRAME :AS 'TICK :PARAMS '(:FLOAT))");
	}

	@Test
	void packageVarStaysARuntimeVariableRead() {
		// *package* is a genuine dynamic variable read when the form RUNS -- never
		// folded to the package this pass has current (the model before 2026-08-15
		// resolved it to 'CL-USER); the quoted spelling is the same symbol.
		assertThat(resolve("*package*")).isEqualTo("*PACKAGE*");
		assertThat(resolve("(cl:car cl:*package*)")).isEqualTo("(CAR *PACKAGE*)");
		assertThat(resolve("'*package*")).isEqualTo("'*PACKAGE*");
	}

	@Test
	void inPackageAcceptsKeywordAndBareSymbolAndAssignsTheRuntimeVariable() {
		// The directive is consumed here, and leaves the run-time assignment in its
		// place so a defun reading *package* at CALL time sees the switch.
		assertThat(resolve("(in-package :rontolisp)")).isEqualTo("(SETQ *PACKAGE* :RONTOLISP)");
		assertThat(resolve("(in-package rontolisp)")).isEqualTo("(SETQ *PACKAGE* :RONTOLISP)");
	}

	@Test
	void popPackageMarkerRestoresTheRuntimeVariableToo() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :loaded (:use :cl))");
		assertThat(resolve(resolver, "(%push-package)")).isEqualTo("'CL-USER");
		assertThat(resolve(resolver, "(in-package :loaded)")).isEqualTo("(SETQ *PACKAGE* :LOADED)");
		assertThat(resolve(resolver, "(%pop-package)")).isEqualTo("(SETQ *PACKAGE* :CL-USER)");
		assertThat(resolve(resolver, "(f x)")).isEqualTo("(F X)");
	}

	@Test
	void quoteDatumInClUserKeepsBareNames() {
		// Quoted data resolves like any other symbol position; in cl-user (which uses cl
		// and whose own canonical spelling is bare) that is the identity.
		assertThat(resolve("'(car x if)")).isEqualTo("'(CAR X IF)");
	}

	// Data position is more permissive than code position: the same two names are an
	// error to CALL in a package that does not use cl (the test below), but reading them
	// quoted just interns them in that package, as Common Lisp's reader does.
	@Test
	void quoteDatumInRontolispInternsInRontolisp() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(in-package :rontolisp)");
		assertThat(resolve(resolver, "'(car x)")).isEqualTo("'(RONTOLISP::CAR RONTOLISP::X)");
	}

	@Test
	void unqualifiedClSymbolInRontolispIsRejected() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(in-package :rontolisp)");
		assertThatThrownBy(() -> resolve(resolver, "(car x)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("use CL:CAR");
	}

	@Test
	void unqualifiedPackageVarInRontolispIsRejected() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(in-package :rontolisp)");
		assertThatThrownBy(() -> resolve(resolver, "*package*")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("use CL:*PACKAGE*");
	}

	@Test
	void inPackageUnknownPackageIsRejected() {
		assertThatThrownBy(() -> resolve("(in-package foo)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("No such package: FOO");
	}

	@Test
	void gensymStyleSymbolIsNotPackageQualified() {
		// A gensym-generated "#:g1" contains a colon but must not be parsed as the
		// package "#"; it stays a plain user symbol (expanded macro bodies reach the
		// resolver on the compile path).
		assertThat(resolve("(let ((#:g1 1)) #:g1)")).isEqualTo("(LET ((#:G1 1)) #:G1)");
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
	void torchLibraryFormsAreAResolverFixedPoint() {
		// TorchLibrary splices its forms both before resolution (the compile-path
		// pre-pass) and after it (the interpreter's lazy load), which is only sound
		// while torch.lisp is written in canonical shape: resolving it must be a
		// no-op.
		PackageResolver resolver = new PackageResolver();
		for (LispVal form : am.ik.rontolisp.eval.TorchLibrary.forms()) {
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
	void geomLibraryFormsAreAResolverFixedPoint() {
		// GeomLibrary splices its forms into programs both before resolution (the
		// compile-path pre-pass) and after it (the interpreter's lazy load), which is
		// only sound while geom.lisp is written in canonical shape: resolving it must
		// be a no-op.
		PackageResolver resolver = new PackageResolver();
		for (LispVal form : am.ik.rontolisp.eval.GeomLibrary.forms()) {
			assertThat(resolver.resolve(form).print()).isEqualTo(form.print());
		}
	}

	@Test
	void metalLibraryFormsAreAResolverFixedPoint() {
		// MetalLibrary splices its forms into programs both before resolution (the
		// compile-path pre-pass) and after it (the interpreter's lazy load), which is
		// only sound while metal.lisp is written in canonical shape: resolving it must
		// be a no-op.
		PackageResolver resolver = new PackageResolver();
		for (LispVal form : am.ik.rontolisp.eval.MetalLibrary.forms()) {
			assertThat(resolver.resolve(form).print()).isEqualTo(form.print());
		}
	}

	@Test
	void sceneLibraryFormsAreAResolverFixedPoint() {
		// SceneLibrary, the same way.
		PackageResolver resolver = new PackageResolver();
		for (LispVal form : am.ik.rontolisp.eval.SceneLibrary.forms()) {
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
		assertThat(resolve(resolver, "(defpackage :mypkg (:use :cl) (:export :greet))")).isEqualTo("'MYPKG");
		// defpackage does not switch the current package.
		assertThat(resolve(resolver, "(f)")).isEqualTo("(F)");
		assertThat(resolve(resolver, "(in-package :mypkg)")).isEqualTo("(SETQ *PACKAGE* :MYPKG)");
		assertThat(resolve(resolver, "(f)")).isEqualTo("(MYPKG::F)");
	}

	@Test
	void definePackageIsConsumedLikeDefpackage() {
		// dbi spells its package headers uiop:define-package and trivial-utf-8 spells
		// pax:define-package -- both are defpackage in the variant's clothing and are
		// consumed exactly like one.
		PackageResolver resolver = new PackageResolver();
		assertThat(resolve(resolver, "(uiop:define-package :dp1 (:use :cl) (:export :f))")).isEqualTo("'DP1");
		assertThat(resolve(resolver, "(pax:define-package :dp2 (:use :cl))")).isEqualTo("'DP2");
		resolve(resolver, "(in-package :dp1)");
		assertThat(resolve(resolver, "(defun f (x) x)")).isEqualTo("(DEFUN DP1:F (DP1::X) DP1::X)");
	}

	@Test
	void aBareDefinePackageIsNotTheVariant() {
		// Only the uiop/mgl-pax qualified spellings are the defpackage variant; a bare
		// define-package is an ordinary user symbol and stays a call form.
		assertThat(resolve("(define-package :x)")).isEqualTo("(DEFINE-PACKAGE :X)");
	}

	@Test
	void definePackageUseReexportUsesAndReexports() {
		// dbi's package header: (:use-reexport #:dbi.error ...) uses the packages AND
		// re-exports their external symbols, so a dbi:connection-error reference
		// resolves to the dbi.error symbol. define-package only -- the clause stays
		// unsupported in plain defpackage.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :re-src (:use :cl) (:export :frob))");
		resolve(resolver, "(uiop:define-package :re-facade (:use :cl) (:use-reexport :re-src))");
		resolve(resolver, "(in-package :cl-user)");
		assertThat(resolve(resolver, "(re-facade:frob 1)")).isEqualTo("(RE-SRC:FROB 1)");
		assertThatThrownBy(() -> resolve(resolver, "(defpackage :plain (:use-reexport :re-src))"))
			.isInstanceOf(LispPackageException.class)
			.hasMessageContaining(":USE-REEXPORT");
	}

	@Test
	void shadowingImportFromWinsOverTheUseList() {
		// dbd-postgres takes database-error-message from cl-postgres-error over the
		// one its use list inherits: the shadowing import must resolve to the named
		// source package's symbol.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :err-a (:use :cl) (:export :oops))");
		resolve(resolver, "(defpackage :err-b (:use :cl) (:export :oops))");
		resolve(resolver, "(defpackage :consumer (:use :cl :err-a) (:shadowing-import-from :err-b :oops))");
		resolve(resolver, "(in-package :consumer)");
		assertThat(resolve(resolver, "(oops 1)")).isEqualTo("(ERR-B:OOPS 1)");
	}

	@Test
	void paxDefsectionExportsItsEntries() {
		// mgl-pax's defsection autoexports each (SYMBOL LOCATIVE) entry -- and that is
		// trivial-utf-8's ONLY export mechanism (its define-package has no :export
		// clause), so uuid's trivial-utf-8:string-to-utf-8-bytes reference depends on
		// this. Docstrings and the (:title ...) option are not entries.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(pax:define-package :tu8 (:use :cl))");
		resolve(resolver, "(in-package :tu8)");
		resolve(resolver, "(pax:defsection @manual (:title \"Manual\") \"docs\" (frob function) (@sub pax:section))");
		resolve(resolver, "(in-package :cl-user)");
		// The autoexport is an export DIRECTIVE, so it grants accessibility under the
		// single colon while the symbol keeps the spelling its package was declared with
		// (no :export clause here, so the internal one). That is what makes the reference
		// name the package's own defun wherever the section sits in the file -- upstream
		// puts it at the END, after the definitions it documents.
		assertThat(resolve(resolver, "(tu8:frob 1)")).isEqualTo("(TU8::FROB 1)");
		assertThat(resolve(resolver, "tu8:@sub")).isEqualTo("TU8::@SUB");
	}

	@Test
	void defpackageExportedDefunIsExternalUnexportedIsInternal() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :mypkg (:use :cl) (:export :greet))");
		resolve(resolver, "(in-package :mypkg)");
		// The exported name is owned and external: the canonical spelling is
		// single-colon.
		assertThat(resolve(resolver, "(defun greet (x) (car x))"))
			.isEqualTo("(DEFUN MYPKG:GREET (MYPKG::X) (CAR MYPKG::X))");
		// A name interned later (not in the :export clause) is internal.
		assertThat(resolve(resolver, "(defun helper (x) x)")).isEqualTo("(DEFUN MYPKG::HELPER (MYPKG::X) MYPKG::X)");
		resolve(resolver, "(in-package :cl-user)");
		assertThat(resolve(resolver, "(mypkg:greet '(1))")).isEqualTo("(MYPKG:GREET '(1))");
		assertThat(resolve(resolver, "#'mypkg:greet")).isEqualTo("#'MYPKG:GREET");
		assertThatThrownBy(() -> resolve(resolver, "(mypkg:helper 1)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("The symbol HELPER is not external in the MYPKG package")
			.hasMessageContaining("use MYPKG::HELPER");
		assertThat(resolve(resolver, "(mypkg::helper 1)")).isEqualTo("(MYPKG::HELPER 1)");
	}

	@Test
	void defpackageWithoutUseClauseRequiresQualifiedCl() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :bare (:export :f))");
		resolve(resolver, "(in-package :bare)");
		assertThatThrownBy(() -> resolve(resolver, "(car x)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("use CL:CAR");
		assertThat(resolve(resolver, "(cl:car cl:*package*)")).isEqualTo("(CAR *PACKAGE*)");
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
		assertThat(resolve(resolver, "(pub)")).isEqualTo("(BASE:PUB)");
		// ... but the internal one is not: an unqualified priv interns as client's own
		// symbol instead (CL: use makes only external symbols accessible).
		assertThat(resolve(resolver, "(priv)")).isEqualTo("(CLIENT::PRIV)");
		assertThat(resolve(resolver, "(base::priv)")).isEqualTo("(BASE::PRIV)");
	}

	@Test
	void babelSpellingsOfTheBabelEncodingsMembersResolveToTheirHome() {
		// Real babel's package :uses babel-encodings and re-exports
		// *default-character-encoding* / list-character-encodings, so both spellings
		// are ONE symbol. The shim's defvar spells babel-encodings:, http-body's
		// detect-charset defaults from babel: -- without the import redirect the
		// babel: spelling minted a distinct global nothing binds (a loud undefined
		// symbol on the compile paths, an unbound-variable landmine on the
		// interpreter).
		assertThat(resolve("babel:*default-character-encoding*"))
			.isEqualTo("BABEL-ENCODINGS:*DEFAULT-CHARACTER-ENCODING*");
		assertThat(resolve("(babel:list-character-encodings)")).isEqualTo("(BABEL-ENCODINGS:LIST-CHARACTER-ENCODINGS)");
		// The redirect covers EVERY babel-encodings external, not a hand-picked pair:
		// the mapping protocol and the condition hierarchy are spelled both ways by
		// one consumer (dexador imports character-decoding-error from babel and
		// *suppress-character-coding-errors* from babel-encodings), and a
		// handler-case naming the babel: spelling must select the type the shim's
		// define-condition defined under the babel-encodings: one.
		assertThat(resolve("(babel:lookup-mapping m e)")).isEqualTo("(BABEL-ENCODINGS:LOOKUP-MAPPING M E)");
		assertThat(resolve("(babel:decoder m)")).isEqualTo("(BABEL-ENCODINGS:DECODER M)");
		assertThat(resolve("babel:character-decoding-error")).isEqualTo("BABEL-ENCODINGS:CHARACTER-DECODING-ERROR");
		assertThat(resolve("babel:*suppress-character-coding-errors*"))
			.isEqualTo("BABEL-ENCODINGS:*SUPPRESS-CHARACTER-CODING-ERRORS*");
		// babel's own two: the mapping table and the character type are defined in
		// babel proper upstream, so they stay owned here (and stay external, since a
		// defpackage :import-from resolves through the external list).
		assertThat(resolve("babel:*string-vector-mappings*")).isEqualTo("BABEL:*STRING-VECTOR-MAPPINGS*");
		assertThat(resolve("'babel:unicode-char")).isEqualTo("'BABEL:UNICODE-CHAR");
	}

	@Test
	void closerCommonLispQualifiedMembersResolveToTheirHomePackages() {
		// closer-common-lisp (nickname c2cl) is the flat re-export of the cl externals
		// overlaid with the closer-mop externals: each member resolves to its HOME
		// package's canonical spelling, closer-mop winning collisions (class-name).
		assertThat(resolve("(c2cl:mapcar f x)")).isEqualTo("(MAPCAR F X)");
		assertThat(resolve("(closer-common-lisp:class-slots c)")).isEqualTo("(CLOSER-MOP:CLASS-SLOTS C)");
		assertThat(resolve("(c2cl:class-name c)")).isEqualTo("(CLOSER-MOP:CLASS-NAME C)");
		assertThat(resolve("(c2cl:defclass foo nil nil)")).isEqualTo("(DEFCLASS FOO NIL NIL)");
	}

	@Test
	void usingCloserCommonLispMakesClAndCloserMopVisible() {
		// (:use :closer-common-lisp) -- postmodern's DAO package shape -- implies
		// (:use :cl), and the closer-mop members inherit through the re-export.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :dao (:use :closer-common-lisp))");
		resolve(resolver, "(in-package :dao)");
		assertThat(resolve(resolver, "(defun probe (c) (mapcar #'slot-definition-name (class-slots c)))")).isEqualTo(
				"(DEFUN DAO::PROBE (DAO::C) (MAPCAR #'CLOSER-MOP:SLOT-DEFINITION-NAME (CLOSER-MOP:CLASS-SLOTS DAO::C)))");
		// The inherited-cl double-colon tolerance follows the implied use too.
		assertThat(resolve(resolver, "(dao::car dao::x)")).isEqualTo("(CAR DAO::X)");
	}

	@Test
	void useListReExportResolvesToTheHomePackage() {
		// A defpackage :export of a symbol inherited from a used package is a
		// re-export (recorded as an import); a package USING the re-exporter must
		// resolve the name to its home package, not the re-exporter's spelling.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :home (:use :cl) (:export :thing))");
		resolve(resolver, "(defpackage :facade (:use :cl :home) (:export :thing))");
		resolve(resolver, "(defpackage :client (:use :cl :facade))");
		resolve(resolver, "(in-package :client)");
		assertThat(resolve(resolver, "(thing)")).isEqualTo("(HOME:THING)");
	}

	@Test
	void reExportOfARedirectedMemberRecordsItsTrueHome() {
		// postmodern's MOP-build shape: (:use :closer-common-lisp) + (:export
		// #:class-finalized-p). c2cl holds class-finalized-p only as a REDIRECT to
		// closer-mop, so the re-export must follow that chain to the true home --
		// recording c2cl itself would spell the package's own references
		// CLOSER-COMMON-LISP:CLASS-FINALIZED-P, a spelling no definition lives under.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :pomo (:use :closer-common-lisp) (:export :class-finalized-p))");
		resolve(resolver, "(in-package :pomo)");
		assertThat(resolve(resolver, "(class-finalized-p c)")).isEqualTo("(CLOSER-MOP:CLASS-FINALIZED-P POMO::C)");
		assertThat(resolve(resolver, "(pomo:class-finalized-p c)")).isEqualTo("(CLOSER-MOP:CLASS-FINALIZED-P POMO::C)");
		// A package using the re-exporter follows the same chain through usedExport.
		resolve(resolver, "(defpackage :pomo-client (:use :cl :pomo))");
		resolve(resolver, "(in-package :pomo-client)");
		assertThat(resolve(resolver, "(class-finalized-p c)"))
			.isEqualTo("(CLOSER-MOP:CLASS-FINALIZED-P POMO-CLIENT::C)");
	}

	@Test
	void importFromARedirectedMemberRecordsItsTrueHome() {
		// The :import-from spelling of the same chain: importing a member the source
		// package itself holds only as a redirect must land on the true home.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :impdao (:use :cl) (:import-from :closer-common-lisp :class-finalized-p))");
		resolve(resolver, "(in-package :impdao)");
		assertThat(resolve(resolver, "(class-finalized-p c)")).isEqualTo("(CLOSER-MOP:CLASS-FINALIZED-P IMPDAO::C)");
	}

	@Test
	void defpackageAcceptsStringAndBareSymbolDesignators() {
		PackageResolver resolver = new PackageResolver();
		assertThat(resolve(resolver, "(defpackage \"STRPKG\" (:use cl) (:export \"F\" g :h))")).isEqualTo("'STRPKG");
		resolve(resolver, "(in-package :strpkg)");
		assertThat(resolve(resolver, "(defun f () 1)")).isEqualTo("(DEFUN STRPKG:F NIL 1)");
		assertThat(resolve(resolver, "(defun g () 2)")).isEqualTo("(DEFUN STRPKG:G NIL 2)");
		assertThat(resolve(resolver, "(defun h () 3)")).isEqualTo("(DEFUN STRPKG:H NIL 3)");
	}

	@Test
	void defpackageOverAnExistingPackageModifiesIt() {
		// CLHS 11.1.2.1: defpackage over a package that already exists modifies it.
		// Upstream cl+ssl opens with its own (defpackage :cl+ssl ...) over the CL+SSL
		// package rontolisp pre-seeds for the shim, and a file re-read in one session
		// declares its package twice.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :mypkg (:use :cl) (:export #:foo))");
		assertThat(resolve(resolver, "(defpackage :mypkg (:export #:bar))")).isEqualTo("'MYPKG");
		// Both the first definition's export and the second's are external.
		assertThat(resolve(resolver, "(mypkg:foo)")).isEqualTo("(MYPKG:FOO)");
		assertThat(resolve(resolver, "(mypkg:bar)")).isEqualTo("(MYPKG:BAR)");
		// The first definition's use list survives a second that does not repeat it.
		resolve(resolver, "(in-package :mypkg)");
		assertThat(resolve(resolver, "(car x)")).isEqualTo("(CAR MYPKG::X)");
	}

	@Test
	void defpackageRepeatingItsOwnNicknameIsAccepted() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :mypackage (:nicknames :mp) (:export :greet))");
		assertThat(resolve(resolver, "(defpackage :mypackage (:nicknames :mp))")).isEqualTo("'MYPACKAGE");
		assertThat(resolve(resolver, "(mp:greet)")).isEqualTo("(MYPACKAGE:GREET)");
	}

	@Test
	void usePackageMakesTheExternalSymbolsOfAPackageVisibleUnqualified() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :mypkg (:use :cl) (:export #:foo))");
		resolve(resolver, "(in-package :mypkg)");
		resolve(resolver, "(defun foo () 1)");
		resolve(resolver, "(in-package :cl-user)");
		// Before the use-package the bare name is a cl-user symbol of its own.
		assertThat(resolve(resolver, "(foo)")).isEqualTo("(FOO)");
		assertThat(resolve(resolver, "(use-package :mypkg)")).isEqualTo("T");
		assertThat(resolve(resolver, "(foo)")).isEqualTo("(MYPKG:FOO)");
		// An internal symbol stays invisible: only externals are inherited.
		assertThat(resolve(resolver, "(bar)")).isEqualTo("(BAR)");
	}

	@Test
	void usePackageAcceptsADesignatorListAndATargetPackage() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :apkg (:use :cl) (:export #:af))");
		resolve(resolver, "(defpackage :bpkg (:use :cl) (:export #:bf))");
		resolve(resolver, "(defpackage :cpkg (:use :cl))");
		assertThat(resolve(resolver, "(use-package '(:apkg \"BPKG\") :cpkg)")).isEqualTo("T");
		// The target package took both, and the current package (cl-user) took none.
		assertThat(resolve(resolver, "(af)")).isEqualTo("(AF)");
		resolve(resolver, "(in-package :cpkg)");
		assertThat(resolve(resolver, "(list (af) (bf))")).isEqualTo("(LIST (APKG:AF) (BPKG:BF))");
	}

	@Test
	void usePackageIsIdempotentAndAcceptsNicknames() {
		PackageResolver resolver = new PackageResolver();
		assertThat(resolve(resolver, "(use-package :common-lisp)")).isEqualTo("T");
		assertThat(resolve(resolver, "(use-package :cl)")).isEqualTo("T");
		assertThat(resolve(resolver, "(car x)")).isEqualTo("(CAR X)");
	}

	@Test
	void usePackageUnknownPackageIsRejected() {
		assertThatThrownBy(() -> resolve("(use-package :nosuch)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("No such package: NOSUCH");
		assertThatThrownBy(() -> resolve("(use-package :cl :nosuch)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("No such package: NOSUCH");
		assertThatThrownBy(() -> resolve("(use-package :cl-user)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("Cannot USE-PACKAGE CL-USER in itself");
		assertThatThrownBy(() -> resolve("(use-package)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("USE-PACKAGE expects a package designator");
	}

	@Test
	void usePackageWithAComputedDesignatorStaysARuntimeCall() {
		// Nothing to consume at compile time: the call is left for the interpreter.
		assertThat(resolve("(use-package (find-my-package))")).isEqualTo("(USE-PACKAGE (FIND-MY-PACKAGE))");
	}

	@Test
	void importMakesASymbolAccessibleUnqualified() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :srcpkg (:use :cl) (:export #:pub))");
		resolve(resolver, "(in-package :srcpkg)");
		resolve(resolver, "(in-package :cl-user)");
		// Before the import the bare name is a cl-user symbol of its own.
		assertThat(resolve(resolver, "(pub)")).isEqualTo("(PUB)");
		assertThat(resolve(resolver, "(import 'srcpkg:pub)")).isEqualTo("T");
		assertThat(resolve(resolver, "(pub)")).isEqualTo("(SRCPKG:PUB)");
		// A QUOTED list, an INTERNAL symbol, and an explicit target package.
		resolve(resolver, "(defpackage :tgtpkg (:use :cl))");
		assertThat(resolve(resolver, "(import '(srcpkg::priv) :tgtpkg)")).isEqualTo("T");
		assertThat(resolve(resolver, "(priv)")).isEqualTo("(PRIV)");
		resolve(resolver, "(in-package :tgtpkg)");
		assertThat(resolve(resolver, "(priv)")).isEqualTo("(SRCPKG::PRIV)");
	}

	@Test
	void exportAfterTheDefinitionKeepsOneSpellingForTheSymbol() {
		// A symbol IS its canonical spelling here, so export must not re-key it: it
		// grants ACCESSIBILITY under the single colon, and the symbol keeps the spelling
		// its package was DECLARED with. Otherwise the defun below is SPIKE::MY-FN while
		// every later spike:my-fn call site names a symbol nothing defines.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :spike (:use :cl))");
		assertThat(resolve(resolver, "(defun spike::my-fn (x) x)")).isEqualTo("(DEFUN SPIKE::MY-FN (X) X)");
		// Before the export the single colon is an error, as in Common Lisp.
		assertThatThrownBy(() -> resolve(resolver, "(spike:my-fn 1)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("is not external");
		assertThat(resolve(resolver, "(export '(spike::my-fn) :spike)")).isEqualTo("T");
		assertThat(resolve(resolver, "(spike:my-fn 1)")).isEqualTo("(SPIKE::MY-FN 1)");
		assertThat(resolve(resolver, "(spike::my-fn 1)")).isEqualTo("(SPIKE::MY-FN 1)");
		// A definition made AFTER the export spells the same way, so the two agree
		// whichever side of the directive they sit on.
		assertThat(resolve(resolver, "(defvar spike::*v* 1)")).isEqualTo("(DEFVAR SPIKE::*V* 1)");
		assertThat(resolve(resolver, "(export '(spike::*v*) :spike)")).isEqualTo("T");
		assertThat(resolve(resolver, "spike:*v*")).isEqualTo("SPIKE::*V*");
	}

	@Test
	void unexportKeepsTheDeclaredSpellingToo() {
		// The mirror image: unexport drops accessibility under the single colon, and the
		// symbol keeps the spelling the :export clause declared -- so a definition made
		// after it still names the same symbol as one made before.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :unexp (:use :cl) (:export #:a))");
		assertThat(resolve(resolver, "(defun unexp::a () 1)")).isEqualTo("(DEFUN UNEXP:A NIL 1)");
		assertThat(resolve(resolver, "(unexport '(unexp::a) :unexp)")).isEqualTo("T");
		assertThat(resolve(resolver, "(unexp::a)")).isEqualTo("(UNEXP:A)");
		assertThatThrownBy(() -> resolve(resolver, "(unexp:a)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("is not external");
	}

	@Test
	void importOfAnUnqualifiedSymbolIsANoOp() {
		// The symbol is already the current package's own, as in Common Lisp.
		PackageResolver resolver = new PackageResolver();
		assertThat(resolve(resolver, "(import 'plain)")).isEqualTo("T");
		assertThat(resolve(resolver, "(plain)")).isEqualTo("(PLAIN)");
	}

	@Test
	void importUnknownPackageIsRejectedAndAComputedArgumentStaysARuntimeCall() {
		assertThatThrownBy(() -> resolve("(import 'nosuch:f)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("No such package: NOSUCH");
		assertThat(resolve("(import (compute-symbol))")).isEqualTo("(IMPORT (COMPUTE-SYMBOL))");
	}

	@Test
	void runtimePackageUseTableCarriesEveryRegisteredPackage() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :upkg (:use :cl))");
		java.util.Map<String, java.util.List<String>> table = resolver.runtimePackageUseTable();
		assertThat(table).containsEntry("UPKG", java.util.List.of("CL"))
			.containsEntry("CL", java.util.List.of())
			.containsEntry("CL-USER", java.util.List.of("CL"))
			.containsEntry("KEYWORD", java.util.List.of());
	}

	@Test
	void defpackageUnknownUsedPackageIsRejected() {
		assertThatThrownBy(() -> resolve("(defpackage :mypkg (:use :nosuch))")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("No such package: NOSUCH");
	}

	@Test
	void defpackageUnsupportedClauseIsRejected() {
		// :shadowing-import-from names a package that must exist, like :import-from.
		assertThatThrownBy(() -> resolve("(defpackage :mypkg (:shadowing-import-from :no-such-pkg :f))"))
			.isInstanceOf(LispPackageException.class)
			.hasMessageContaining("No such package");
		assertThatThrownBy(() -> resolve("(defpackage :mypkg (:intern :f))")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("Unsupported DEFPACKAGE clause: :INTERN");
	}

	@Test
	void defpackageDocumentationAndSizeAreIgnored() {
		PackageResolver resolver = new PackageResolver();
		assertThat(resolve(resolver, "(defpackage :mypkg (:use :cl) (:documentation \"doc\") (:size 10))"))
			.isEqualTo("'MYPKG");
	}

	@Test
	void defpackageNicknamesResolveLikeTheCanonicalName() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :mypackage (:use :cl) (:nicknames :mp :mypkg2) (:export :greet))");
		assertThat(resolve(resolver, "(mp:greet)")).isEqualTo("(MYPACKAGE:GREET)");
		assertThat(resolve(resolver, "(mypkg2:greet)")).isEqualTo("(MYPACKAGE:GREET)");
		assertThat(resolve(resolver, "(in-package :mp)")).isEqualTo("(SETQ *PACKAGE* :MYPACKAGE)");
		// A defpackage whose NAME is another package's nickname is still refused:
		// modifying through it would silently apply the definition to a package of a
		// different name.
		assertThatThrownBy(() -> resolve(resolver, "(defpackage :mp)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("Package already exists: MP");
	}

	@Test
	void defpackageNicknameCollidingWithExistingPackageIsRejected() {
		assertThatThrownBy(() -> resolve("(defpackage :mypkg (:nicknames :cl-user))"))
			.isInstanceOf(LispPackageException.class)
			.hasMessageContaining("Package already exists: CL-USER");
	}

	@Test
	void commonLispNicknamesResolveToClAndClUser() {
		PackageResolver resolver = new PackageResolver();
		assertThat(resolve(resolver, "(common-lisp:car x)")).isEqualTo("(CAR X)");
		assertThat(resolve(resolver, "(in-package :common-lisp-user)")).isEqualTo("(SETQ *PACKAGE* :CL-USER)");
		resolve(resolver, "(defpackage :mypkg (:use :common-lisp) (:export :f))");
		resolve(resolver, "(in-package :mypkg)");
		assertThat(resolve(resolver, "(car x)")).isEqualTo("(CAR MYPKG::X)");
	}

	@Test
	void builtinNicknamesResolveToRontolispAndLinalg() {
		PackageResolver resolver = new PackageResolver();
		assertThat(resolve(resolver, "(rl:version)")).isEqualTo("(RONTOLISP:VERSION)");
		assertThat(resolve(resolver, "(rl:json-parse s)")).isEqualTo("(RONTOLISP:JSON-PARSE S)");
		assertThat(resolve(resolver, "(la:zeros 2 2)")).isEqualTo("(LINALG:ZEROS 2 2)");
		assertThat(resolve(resolver, "(in-package :rl)")).isEqualTo("(SETQ *PACKAGE* :RONTOLISP)");
	}

	@Test
	void builtinNicknamesAreReservedLikePackageNames() {
		assertThatThrownBy(() -> resolve("(defpackage :rl)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("Package already exists: RL");
		assertThatThrownBy(() -> resolve("(defpackage :mypkg (:nicknames :la))"))
			.isInstanceOf(LispPackageException.class)
			.hasMessageContaining("Package already exists: LA");
	}

	@Test
	void defpackageUninternedSymbolDesignatorsAreAccepted() {
		// The portable defpackage idiom spells every designator #:name.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage #:mypkg (:use #:common-lisp) (:nicknames #:mp) (:export #:greet))");
		resolve(resolver, "(in-package #:mypkg)");
		assertThat(resolve(resolver, "(defun greet () (car x))")).isEqualTo("(DEFUN MYPKG:GREET NIL (CAR MYPKG::X))");
		assertThat(resolve(resolver, "(mp:greet)")).isEqualTo("(MYPKG:GREET)");
	}

	@Test
	void defpackageImportFromMapsToTheSourcePackage() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :base (:use :cl) (:export :pub))");
		resolve(resolver, "(defpackage :client (:use :cl) (:import-from :base :pub))");
		resolve(resolver, "(in-package :client)");
		// The imported name resolves unqualified to the source package's canonical
		// spelling, and client:pub redirects there too.
		assertThat(resolve(resolver, "(pub)")).isEqualTo("(BASE:PUB)");
		assertThat(resolve(resolver, "(client::pub)")).isEqualTo("(BASE:PUB)");
	}

	@Test
	void defpackageExportOfAnInheritedNameReExportsTheSourceSymbol() {
		// Common Lisp's (:use :base) + (:export #:pub) re-exports the very same BASE:PUB.
		// Resolution here is textual and resolveUnqualified checks the package's own
		// export list BEFORE the use list, so without the defpackage-time redirect
		// FRONT:PUB was a distinct name and every call to it was undefined -- which is
		// what postmodern (:use :s-sql) + (:export #:sql #:sql-compile) hit.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :base (:use :cl) (:export :pub))");
		resolve(resolver, "(defpackage :front (:use :cl :base) (:export :pub :own))");
		resolve(resolver, "(in-package :front)");
		assertThat(resolve(resolver, "(pub)")).isEqualTo("(BASE:PUB)");
		assertThat(resolve(resolver, "(front:pub)")).isEqualTo("(BASE:PUB)");
		// A name the package really owns is unaffected.
		assertThat(resolve(resolver, "(own)")).isEqualTo("(FRONT:OWN)");
	}

	@Test
	void quotedDataResolvesAgainstTheCurrentPackage() {
		// The reader interns a quoted datum's symbols in the current package, so a data
		// TABLE naming a macro of that package still names it after resolution.
		// postmodern's *result-styles* is exactly this: a defparameter of triples whose
		// third element the `query` macro splices into its own expansion.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :styles (:use :cl) (:export :run))");
		resolve(resolver, "(in-package :styles)");
		assertThat(resolve(resolver, "(defparameter *table* '((:rows reader all-rows)))"))
			.isEqualTo("(DEFPARAMETER STYLES::*TABLE* '((:ROWS STYLES::READER STYLES::ALL-ROWS)))");
		assertThat(resolve(resolver, "(defmacro all-rows (form) form)")).startsWith("(DEFMACRO STYLES::ALL-ROWS");
		// Keywords, t/nil and cl symbols keep their canonical spelling.
		assertThat(resolve(resolver, "'(:a nil t car)")).isEqualTo("'(:A NIL T CAR)");
	}

	@Test
	void defpackageImportFromClWorksWithoutUsingCl() {
		// The (:import-from #:common-lisp ...) idiom of use-nothing libraries.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :bare (:import-from #:common-lisp #:car #:defun))");
		resolve(resolver, "(in-package :bare)");
		assertThat(resolve(resolver, "(car x)")).isEqualTo("(CAR BARE::X)");
		assertThatThrownBy(() -> resolve(resolver, "(cdr x)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("Undefined symbol: CDR");
	}

	@Test
	void defpackageImportFromUnknownPackageIsRejected() {
		assertThatThrownBy(() -> resolve("(defpackage :mypkg (:import-from :nosuch :f))"))
			.isInstanceOf(LispPackageException.class)
			.hasMessageContaining("No such package: NOSUCH");
		assertThatThrownBy(() -> resolve("(defpackage :mypkg (:import-from))")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining(":IMPORT-FROM expects a package name");
	}

	@Test
	void uninternedSymbolPassesThroughUnresolved() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(in-package :rontolisp)");
		assertThat(resolve(resolver, "#:g1")).isEqualTo("#:G1");
	}

	@Test
	void defpackageMalformedClauseIsRejected() {
		assertThatThrownBy(() -> resolve("(defpackage :mypkg :use)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("expects (:use ...) / (:export ...) clauses");
		assertThatThrownBy(() -> resolve("(defpackage)")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("DEFPACKAGE expects a package name");
		assertThatThrownBy(() -> resolve("(defpackage (car x))")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("DEFPACKAGE expects a package name");
	}

	@Test
	void defpackageNestedIsRejected() {
		assertThatThrownBy(() -> resolve("(print (defpackage :mypkg))")).isInstanceOf(LispPackageException.class)
			.hasMessageContaining("DEFPACKAGE is only supported as a literal top-level form");
	}

	@Test
	void defpackageQuotedDatumIsLeftUntouched() {
		assertThat(resolve("'(defpackage :mypkg)")).isEqualTo("'(DEFPACKAGE :MYPKG)");
	}

	@Test
	void defpackageLocalNicknamesRegistersGlobalNicknames() {
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage #:com.example.long-name (:use #:cl) (:export #:run))");
		resolve(resolver, "(defpackage #:my-app (:use #:cl) (:local-nicknames (#:short #:com.example.long-name)))");
		resolve(resolver, "(in-package #:my-app)");
		// Lite: the nickname is global, so the qualified call resolves through it.
		assertThat(resolve(resolver, "(short:run)")).isEqualTo("(COM.EXAMPLE.LONG-NAME:RUN)");
	}

	@Test
	void defpackageLocalNicknamesRequiresAnExistingTarget() {
		PackageResolver resolver = new PackageResolver();
		assertThatThrownBy(() -> resolve(resolver,
				"(defpackage #:my-app (:use #:cl) (:local-nicknames (#:short #:no-such-package)))"))
			.isInstanceOf(LispPackageException.class)
			.hasMessageContaining("No such package");
	}

	@Test
	void variableNamedQuoteDoesNotSwallowTheFollowingArgument() {
		// A call tail beginning with a variable named `quote` must not be re-read as
		// the (quote DATUM) special form: the following argument is code (s-sql's
		// :copy op binds a `quote` group and does (when quote (sql-expand ...))).
		PackageRegistry registry = new PackageRegistry();
		registry.define(new LispPackage("RP", List.of(LispNames.CL_PKG), Set.of()));
		PackageResolver resolver = new PackageResolver(registry);
		resolve(resolver, "(in-package rp)");
		assertThat(resolve(resolver, "(when quote (my-fn (car quote)))"))
			.isEqualTo("(WHEN QUOTE (RP::MY-FN (CAR QUOTE)))");
		// A lambda list (quote) is data in binding position, not a quote form.
		assertThat(resolve(resolver, "(defun use-q (quote) (my-fn (car quote)))"))
			.isEqualTo("(DEFUN RP::USE-Q (QUOTE) (RP::MY-FN (CAR QUOTE)))");
		// A genuine (quote DATUM) element keeps the data exemption.
		assertThat(resolve(resolver, "(my-fn 'some-list)")).isEqualTo("(RP::MY-FN 'RP::SOME-LIST)");
	}

	@Test
	void doubleColonReachesTheInheritedClSymbol() {
		// pkg::name reaches any symbol ACCESSIBLE in pkg, including one inherited
		// from cl: cl-postgres::write-string IS cl:write-string (s-sql spells it so).
		PackageRegistry registry = new PackageRegistry();
		registry.define(new LispPackage("RP", List.of(LispNames.CL_PKG), Set.of()));
		PackageResolver resolver = new PackageResolver(registry);
		assertThat(resolve(resolver, "(rp::write-string s)")).isEqualTo("(WRITE-STRING S)");
		// A member the package owns stays its own internal symbol.
		resolve(resolver, "(in-package rp)");
		resolve(resolver, "(defun my-own (x) x)");
		resolve(resolver, "(in-package cl-user)");
		assertThat(resolve(resolver, "(rp::my-own 1)")).isEqualTo("(RP::MY-OWN 1)");
	}

	@Test
	void newPackageInRegistryResolvesViaUseListAndQualifiesOwnSymbols() {
		// Extensibility: registering a package that uses rontolisp makes its symbols
		// (version) visible unqualified, and the resolution logic is unchanged.
		PackageRegistry registry = new PackageRegistry();
		registry.define(new LispPackage("MYPKG", List.of(LispNames.RONTOLISP_PKG), Set.of("GREET")));
		PackageResolver resolver = new PackageResolver(registry);
		resolve(resolver, "(in-package mypkg)");
		assertThat(resolve(resolver, "(version)")).isEqualTo("(RONTOLISP:VERSION)");
		assertThat(resolve(resolver, "(greet)")).isEqualTo("(MYPKG:GREET)");
		// x is not registered in MYPKG, so it is internal: the canonical spelling is
		// MYPKG::X.
		assertThat(resolve(resolver, "(cl:car x)")).isEqualTo("(CAR MYPKG::X)");
	}

	@Test
	void bareLowerKebabWitImportNameResolvesAgainstAHandWrittenUppercaseExport() {
		// gl.lisp's actual shape (examples/browser/webgl-common/gl.lisp): a HAND-WRITTEN
		// defpackage -- read through the normal upcasing reader, so its :export entry is
		// CREATE-SHADER -- followed by a `(rontolisp:wit-import "gl.wit" :interface
		// "local:webgl/gl")` with NO :package option. WitImportDirective then
		// synthesizes the binding's quoted name directly as `new LispSymbol(member)`
		// from the WIT file's raw lower-kebab label ("create-shader"), bypassing the
		// reader entirely -- unlike wasmImportQuotedNameResolvesInCurrentPackage above,
		// whose `'create-shader` is SOURCE TEXT and therefore already upcased by the
		// time PackageResolver sees it. A call site `(gl:create-shader ...)` elsewhere
		// in the program resolves to GL:CREATE-SHADER (external, uppercase member); the
		// binding's bare reference must resolve to the exact same symbol, or the
		// compiled function is defined under one name and called under another --
		// undefined-function silently downgraded to a WASM "call-time error" stub that
		// traps at runtime.
		PackageResolver resolver = new PackageResolver();
		resolve(resolver, "(defpackage :gl (:use :cl) (:export :create-shader))");
		resolve(resolver, "(in-package :gl)");
		assertThat(resolver.resolve(new LispSymbol("create-shader"))).isEqualTo(new LispSymbol("GL:CREATE-SHADER"));
	}

	@Test
	void aRewrittenFormKeepsTheSourcePositionOfTheFormItResolved() {
		// The cons-identity rule (.kb/source-positions.md) covers a form nothing
		// resolved differently; this is the other half. Under (in-package :probe)
		// essentially every form IS rewritten -- an unqualified name resolves to the
		// package's canonical spelling -- so
		// without inheritance the whole file, from the top-level form down, is conses
		// the provenance table has never seen, and every post-read error about it
		// reports with no position at all.
		SourceProvenance.startRecording();
		try {
			List<LispVal> program = LispReader.readAllFromString("""
					(defpackage :probe
					  (:use :cl))
					(in-package :probe)
					(defun g (x)
					  (list *package* (helper x)))
					""", Features.JVM, "prog.lisp");
			List<LispVal> resolved = new PackageResolver().resolveProgram(program);
			LispVal defun = resolved.get(2);
			assertThat(defun.print())
				.isEqualTo("(DEFUN PROBE::G (PROBE::X) (LIST *PACKAGE* (PROBE::HELPER PROBE::X)))");
			assertThat(SourceProvenance.locate(defun)).isEqualTo(new SourceLocation("prog.lisp", 4, 1));
			LispVal body = ((LispCons) ((LispCons) ((LispCons) defun).cdr()).cdr()).cdr();
			assertThat(SourceProvenance.locate(((LispCons) body).car()))
				.isEqualTo(new SourceLocation("prog.lisp", 5, 3));
		}
		finally {
			SourceProvenance.stopRecording();
		}
	}

	@Test
	void printsBareFollowsAccessibilityInTheCurrentPackage() {
		// The printer's question (CLHS 22.1.3.3.1), answered the way a reference is
		// resolved: a qualified symbol prints bare when an unqualified reference to its
		// name in the current package resolves to it.
		PackageResolver resolver = new PackageResolver();
		resolver.resolveProgram(LispReader.readAllFromString("""
				(defpackage :spa-lib (:use :cl) (:export #:fn))
				(defpackage :spa-lib2 (:use :cl) (:export #:other))
				(defpackage :spa-mid (:use :cl :spa-lib) (:export #:fn))
				(defpackage :spa-app (:use :cl :spa-lib :spa-lib2) (:import-from :spa-lib #:helper) (:shadow #:other))
				(defpackage :spa-app2 (:use :cl :spa-mid))
				"""));
		resolver.setCurrentPackage("SPA-APP");
		assertThat(resolver.printsBare("SPA-APP::OWN-FN")).isTrue();
		assertThat(resolver.printsBare("SPA-LIB:FN")).isTrue();
		assertThat(resolver.printsBare("SPA-LIB::HELPER")).isTrue();
		assertThat(resolver.printsBare("SPA-LIB::INTERNAL")).isFalse();
		assertThat(resolver.printsBare("SPA-LIB2:OTHER")).isFalse();
		assertThat(resolver.printsBare(":KW")).isTrue();
		assertThat(resolver.printsBare("#:G1")).isTrue();
		assertThat(resolver.printsBare("CAR")).isTrue();
		assertThat(resolver.currentPackageIsPristineClUser()).isFalse();
		resolver.setCurrentPackage("SPA-APP2");
		assertThat(resolver.printsBare("SPA-LIB:FN")).isTrue();
		assertThat(resolver.printsBare("SPA-APP::OWN-FN")).isFalse();
		resolver.setCurrentPackage("CL-USER");
		assertThat(resolver.printsBare("SPA-LIB:FN")).isFalse();
		assertThat(resolver.currentPackageIsPristineClUser()).isTrue();
	}

	@Test
	void symbolPrintTableCarriesTheCorrectionsForTheSymbolsTheProgramSpells() {
		// The compile paths bake the use lists and, per package, the symbols the
		// structural rule (own, or used-and-external) gets wrong: an imported internal
		// and a symbol re-exported through an intermediate package are EXTRA, a shadowed
		// name is EXCLUDED. Computed over the symbols that occur in the resolved program.
		PackageResolver resolver = new PackageResolver();
		List<LispVal> resolved = resolver.resolveProgram(LispReader.readAllFromString("""
				(defpackage :spa-lib (:use :cl) (:export #:fn))
				(defpackage :spa-lib2 (:use :cl) (:export #:other))
				(defpackage :spa-mid (:use :cl :spa-lib) (:export #:fn))
				(defpackage :spa-app (:use :cl :spa-lib :spa-lib2) (:import-from :spa-lib #:helper) (:shadow #:other))
				(defpackage :spa-app2 (:use :cl :spa-mid))
				(in-package :spa-app)
				(print (list 'fn 'helper 'other 'spa-lib2:other 'spa-lib::internal))
				(in-package :spa-app2)
				(print 'spa-lib:fn)
				(in-package :cl-user)
				"""));
		SymbolPrintTable table = resolver.symbolPrintTable(resolved);
		assertThat(table.rows()).containsEntry("SPA-APP", List.of("SPA-APP", "SPA-LIB", "SPA-LIB2"))
			.containsEntry("SPA-APP2", List.of("SPA-APP2", "SPA-MID"))
			.containsEntry("CL-USER", List.of("CL-USER"));
		assertThat(table.extra()).containsEntry("SPA-APP", List.of("SPA-LIB::HELPER"))
			.containsEntry("SPA-APP2", List.of("SPA-LIB:FN"));
		assertThat(table.excluded()).containsEntry("SPA-APP", List.of("SPA-LIB2:OTHER"));
		assertThat(table.excluded()).doesNotContainKey("SPA-APP2");
		assertThat(table.clUserPristine()).isTrue();
	}

}
