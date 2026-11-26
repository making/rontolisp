package am.ik.wit;

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WitResolver}: the name resolution a binder needs on top of the
 * parser's lossless, purely syntactic model -- which interface a reference names, and
 * which item defines a {@link WitType.Named} reference.
 *
 * <p>
 * Two properties are load-bearing for the binders ({@code rontolisp:wit-import}): an
 * unresolvable name is {@code null} rather than an exception (the caller turns it into an
 * error naming the WIT file and line), and an AMBIGUOUS reference is {@code null} too --
 * a silent "first one wins" would bind the wrong host function. A {@code use} cycle must
 * terminate rather than hang the compiler.
 */
class WitResolverTest {

	// A trimmed wasi:keyvalue: a freestanding func, a resource carrying a constructor,
	// methods and a static func, and a second interface holding the types the first one
	// pulls in with a `use` clause.
	private static final String KEYVALUE = """
			package wasi:keyvalue@0.2.0;

			interface store {
			  use types.{error};

			  open: func(identifier: string) -> u32;

			  resource bucket {
			    constructor(name: string);
			    get: func(key: string) -> option<string>;
			    set: func(key: string, value: string);
			    list-keys: static func(prefix: string) -> list<string>;
			  }
			}

			interface types {
			  record point { x: s32, y: s32 }

			  variant outcome { hit(string), miss }

			  enum severity { low, high }

			  flags permissions { read, write }

			  resource error;

			  type handle = u32;
			}
			""";

	private static WitResolver resolver(String source) {
		return new WitResolver(WitParser.parse(source));
	}

	// `findInterface` answers null for an unknown reference (pinned on its own below); a
	// test that goes on to RESOLVE against the interface knows the document defines it,
	// so
	// unwrap here rather than feeding a nullable into resolveType/functions.
	private static WitItem.InterfaceDef iface(WitResolver resolver, String reference) {
		return Objects.requireNonNull(resolver.findInterface(reference), reference);
	}

	// "bucket.get:PLAIN" -- the owning resource, the WIT name and how it was declared.
	private static List<String> described(List<WitResolver.Func> funcs) {
		return funcs.stream()
			.map(func -> (func.resource() == null ? "" : func.resource() + ".") + func.def().name() + ":"
					+ func.def().kind())
			.toList();
	}

	@Test
	void indexesEveryInterfaceByItsFullyQualifiedIdInDocumentOrder() {
		assertThat(resolver(KEYVALUE).interfaceIds()).containsExactly("wasi:keyvalue/store@0.2.0",
				"wasi:keyvalue/types@0.2.0");
	}

	@Test
	void keepsTheDocumentItWasBuiltOver() {
		WitDocument document = WitParser.parse(KEYVALUE);
		assertThat(new WitResolver(document).document()).isSameAs(document);
	}

	@Test
	void findsAnInterfaceByItsIdItsUnversionedIdAndItsBareName() {
		WitResolver resolver = resolver(KEYVALUE);
		WitItem.InterfaceDef store = resolver.findInterface("wasi:keyvalue/store@0.2.0");
		assertThat(store).isNotNull();
		assertThat(store.name()).isEqualTo("store");
		// The three spellings a user may write for the same interface.
		assertThat(resolver.findInterface("wasi:keyvalue/store")).isSameAs(store);
		assertThat(resolver.findInterface("store")).isSameAs(store);
	}

	@Test
	void everySpellingOfAnInterfaceCanonicalizesToItsFullyQualifiedId() {
		// What a binder keys a provider registry by: all three spellings name ONE
		// interface, so all three must canonicalize to the one id -- keying by the
		// reference as written would give one interface three registry keys.
		WitResolver resolver = resolver(KEYVALUE);
		assertThat(resolver.canonicalId(iface(resolver, "wasi:keyvalue/store@0.2.0")))
			.isEqualTo("wasi:keyvalue/store@0.2.0");
		assertThat(resolver.canonicalId(iface(resolver, "wasi:keyvalue/store"))).isEqualTo("wasi:keyvalue/store@0.2.0");
		assertThat(resolver.canonicalId(iface(resolver, "store"))).isEqualTo("wasi:keyvalue/store@0.2.0");
		// An interface from ANOTHER document is not this document's to canonicalize.
		WitItem.InterfaceDef foreign = iface(resolver("""
				package other:pkg@1.0.0;

				interface store {
				  ping: func();
				}
				"""), "store");
		assertThat(resolver.canonicalId(foreign)).isNull();
	}

	@Test
	void anUnknownReferenceIsNullRatherThanAnException() {
		WitResolver resolver = resolver(KEYVALUE);
		assertThat(resolver.findInterface("nope")).isNull();
		assertThat(resolver.findInterface("wasi:keyvalue/nope")).isNull();
		// A QUALIFIED reference never falls back to the bare-name index:
		// `other:pkg/store`
		// names another package's store, not this document's.
		assertThat(resolver.findInterface("other:pkg/store")).isNull();
	}

	@Test
	void findsAnInterfaceThroughAWitRef() {
		WitResolver resolver = resolver(KEYVALUE);
		WitItem.InterfaceDef store = resolver.findInterface("store");
		assertThat(resolver.findInterface(new WitRef(new WitPackageName("wasi", "keyvalue", "0.2.0"), "store")))
			.isSameAs(store);
		// A `use types.{...}` clause carries a LOCAL ref, which resolves by bare name.
		assertThat(resolver.findInterface(WitRef.local("types"))).isSameAs(resolver.findInterface("types"));
		// An interface from a dependency the document does not carry: null, not an error.
		assertThat(resolver.findInterface(new WitRef(new WitPackageName("wasi", "io", "0.2.0"), "streams"))).isNull();
	}

	@Test
	void findsInterfacesInsideAnExplicitPackageBlock() {
		// `wasm-tools component wit` prints every referenced package as a `package x:y {
		// ... }` block after the root world, so the index must descend into them.
		WitResolver resolver = resolver("""
				package root:component;

				interface local {
				  ping: func();
				}

				package wasi:keyvalue@0.2.0 {
				  interface store {
				    open: func(identifier: string) -> u32;
				  }
				}
				""");
		assertThat(resolver.interfaceIds()).containsExactly("root:component/local", "wasi:keyvalue/store@0.2.0");
		assertThat(resolver.findInterface("wasi:keyvalue/store@0.2.0")).isNotNull();
		assertThat(resolver.findInterface("store")).isNotNull();
		assertThat(resolver.findInterface("root:component/local")).isNotNull();
	}

	@Test
	void anAmbiguousBareNameResolvesToNull() {
		// Two packages defining `store`: the bare name no longer names ONE interface, so
		// it
		// has to be spelled out -- picking the first would silently bind the wrong host.
		WitResolver resolver = resolver("""
				package a:one@0.1.0 {
				  interface store {
				    get: func(key: string) -> string;
				  }
				}

				package b:two@0.1.0 {
				  interface store {
				    put: func(key: string);
				  }
				}
				""");
		assertThat(resolver.interfaceIds()).containsExactly("a:one/store@0.1.0", "b:two/store@0.1.0");
		assertThat(resolver.findInterface("store")).isNull();
		// Spelled out, each one binds -- with or without its version.
		WitItem.InterfaceDef one = resolver.findInterface("a:one/store@0.1.0");
		WitItem.InterfaceDef two = resolver.findInterface("b:two/store");
		assertThat(one).isNotNull();
		assertThat(two).isNotNull();
		assertThat(described(WitResolver.functions(one))).containsExactly("get:PLAIN");
		assertThat(described(WitResolver.functions(two))).containsExactly("put:PLAIN");
		assertThat(resolver.findInterface("a:one/store")).isSameAs(one);
	}

	@Test
	void theSameInterfaceAtTwoVersionsIsAmbiguousWithoutItsVersion() {
		WitResolver resolver = resolver("""
				package foo:bar@0.1.0 {
				  interface store {
				    get: func(key: string) -> string;
				  }
				}

				package foo:bar@0.2.0 {
				  interface store {
				    get: func(key: string) -> string;
				  }
				}
				""");
		assertThat(resolver.findInterface("foo:bar/store")).isNull();
		assertThat(resolver.findInterface("store")).isNull();
		assertThat(resolver.findInterface("foo:bar/store@0.2.0")).isNotNull();
		assertThat(resolver.findInterface("foo:bar/store@0.1.0"))
			.isNotSameAs(resolver.findInterface("foo:bar/store@0.2.0"));
	}

	@Test
	void resolvesAnInterfacesOwnTypeDefinitions() {
		WitResolver resolver = resolver(KEYVALUE);
		WitItem.InterfaceDef types = iface(resolver, "types");
		assertThat(resolver.resolveType(types, "point")).isInstanceOf(WitItem.RecordDef.class);
		assertThat(resolver.resolveType(types, "outcome")).isInstanceOf(WitItem.VariantDef.class);
		assertThat(resolver.resolveType(types, "severity")).isInstanceOf(WitItem.EnumDef.class);
		assertThat(resolver.resolveType(types, "permissions")).isInstanceOf(WitItem.FlagsDef.class);
		assertThat(resolver.resolveType(types, "error")).isInstanceOf(WitItem.ResourceDef.class);
		assertThat(resolver.resolveType(types, "handle")).isInstanceOfSatisfying(WitItem.TypeAlias.class,
				alias -> assertThat(alias.target()).isEqualTo(new WitType.Prim("u32")));
	}

	@Test
	void anUnresolvableTypeNameIsNull() {
		WitResolver resolver = resolver(KEYVALUE);
		WitItem.InterfaceDef store = iface(resolver, "store");
		assertThat(resolver.resolveType(store, "nope")).isNull();
		// A func is not a type definition: `open` must not resolve as one.
		assertThat(resolver.resolveType(store, "open")).isNull();
	}

	@Test
	void resolvesATypeBroughtInByAUseClause() {
		WitResolver resolver = resolver(KEYVALUE);
		WitItem.InterfaceDef store = iface(resolver, "store");
		WitItem.InterfaceDef types = iface(resolver, "types");
		// `use types.{error};` in `store` -> the `resource error` that `types` defines.
		assertThat(resolver.resolveType(store, "error")).isInstanceOf(WitItem.ResourceDef.class)
			.isSameAs(resolver.resolveType(types, "error"));
		// The clause imports `error` ONLY: the rest of `types` stays invisible in
		// `store`.
		assertThat(resolver.resolveType(store, "point")).isNull();
	}

	@Test
	void reportsTheInterfaceThatDefinesAResolvedType() {
		WitResolver resolver = resolver(KEYVALUE);
		WitItem.InterfaceDef store = iface(resolver, "store");
		WitItem.InterfaceDef types = iface(resolver, "types");
		// A type the interface defines itself: the owner is the interface.
		WitResolver.Owned bucket = Objects.requireNonNull(resolver.resolveOwned(store, "bucket"));
		assertThat(bucket.item()).isInstanceOf(WitItem.ResourceDef.class);
		assertThat(bucket.owner()).isSameAs(store);
		// A type it merely USES: the owner is the interface the `use` clause names, which
		// is the whole point -- a component-model consumer must alias a used resource out
		// of the DEFINING instance rather than re-declare it (a resource is nominal).
		WitResolver.Owned error = Objects.requireNonNull(resolver.resolveOwned(store, "error"));
		assertThat(error.item()).isInstanceOf(WitItem.ResourceDef.class);
		assertThat(error.owner()).isSameAs(types);
		assertThat(resolver.canonicalId(error.owner())).isEqualTo("wasi:keyvalue/types@0.2.0");
		// Unresolvable stays null, exactly as resolveType does.
		assertThat(resolver.resolveOwned(store, "nope")).isNull();
	}

	@Test
	void reportsTheOwnerAndTheOwnersOwnNameForATypeResolvedThroughAnAlias() {
		// `use types.{error as io-error}` -- the shape wasi:http/types writes for
		// wasi:io/error's `error` resource. The local spelling is `io-error`, but the
		// component-model type is the OWNER's: an encoder has to alias `error` (not
		// `io-error`) out of the wasi:io/error instance, because that is the name that
		// instance exports. So resolveOwned must report the definition's own name and the
		// interface it sits in, not the importing scope's alias.
		WitResolver resolver = resolver("""
				package root:component;

				interface api {
				  use types.{error as io-error};

				  fail: func() -> io-error;
				}

				interface types {
				  resource error;
				}
				""");
		WitItem.InterfaceDef api = iface(resolver, "api");
		WitItem.InterfaceDef types = iface(resolver, "types");
		WitResolver.Owned owned = Objects.requireNonNull(resolver.resolveOwned(api, "io-error"));
		assertThat(owned.owner()).isSameAs(types);
		assertThat(owned.item()).isInstanceOfSatisfying(WitItem.ResourceDef.class,
				resource -> assertThat(resource.name()).isEqualTo("error"));
		assertThat(owned.item()).isSameAs(resolver.resolveType(types, "error"));
	}

	@Test
	void resolvesThroughAnAliasedUseName() {
		WitResolver resolver = resolver("""
				package root:component;

				interface api {
				  use types.{error as io-error};

				  fail: func() -> io-error;
				}

				interface types {
				  resource error;
				}
				""");
		WitItem.InterfaceDef api = iface(resolver, "api");
		assertThat(resolver.resolveType(api, "io-error")).isInstanceOf(WitItem.ResourceDef.class)
			.isSameAs(resolver.resolveType(iface(resolver, "types"), "error"));
		// `as` RENAMES: the source spelling is not in scope in the importing interface.
		assertThat(resolver.resolveType(api, "error")).isNull();
	}

	@Test
	void aTypeAUseClauseDidNotImportDoesNotResolveInTheImportingScope() {
		// The strictness that makes threading the DEFINING scope through a type-structure
		// walk necessary rather than merely tidy. This is wasi:http in miniature:
		// `outgoing-handler` uses `error-code` from `types`, and `error-code`'s
		// `DNS-error`
		// case carries a `DNS-error-payload` that `outgoing-handler` never imported and
		// cannot see. A walk that descends into the variant's payloads has therefore left
		// the scope it started in: it must resolve them in `types`, the owner reported
		// for
		// `error-code`. Resolution stays strict here -- a lenient "look everywhere"
		// search
		// would paper over the bug and pick a same-named type from the wrong interface.
		WitResolver resolver = resolver("""
				package wasi:http@0.2.0;

				interface types {
				  record DNS-error-payload {
				    rcode: option<string>,
				    info-code: option<u16>
				  }

				  variant error-code {
				    DNS-timeout,
				    DNS-error(DNS-error-payload),
				    connection-refused
				  }
				}

				interface outgoing-handler {
				  use types.{error-code};

				  handle: func(request: u32) -> result<u32, error-code>;
				}
				""");
		WitItem.InterfaceDef handler = iface(resolver, "outgoing-handler");
		WitItem.InterfaceDef types = iface(resolver, "types");
		// The used type resolves, and hands back the scope its cases are written in.
		WitResolver.Owned errorCode = Objects.requireNonNull(resolver.resolveOwned(handler, "error-code"));
		assertThat(errorCode.item()).isInstanceOf(WitItem.VariantDef.class);
		assertThat(errorCode.owner()).isSameAs(types);
		// Its payload type does NOT: the use clause imported `error-code` and nothing
		// else.
		assertThat(resolver.resolveOwned(handler, "DNS-error-payload")).isNull();
		assertThat(resolver.resolveType(handler, "DNS-error-payload")).isNull();
		// In the owner's scope -- the one the walk continues in -- it does.
		WitResolver.Owned payload = Objects
			.requireNonNull(resolver.resolveOwned(errorCode.owner(), "DNS-error-payload"));
		assertThat(payload.item()).isInstanceOf(WitItem.RecordDef.class);
		assertThat(payload.owner()).isSameAs(types);
	}

	@Test
	void resolvesTransitivelyThroughAChainOfUseClauses() {
		WitResolver resolver = resolver("""
				package root:component;

				interface a {
				  use b.{token};

				  ping: func(t: token);
				}

				interface b {
				  use c.{token};
				}

				interface c {
				  type token = u64;
				}
				""");
		assertThat(resolver.resolveType(iface(resolver, "a"), "token")).isInstanceOf(WitItem.TypeAlias.class)
			.isSameAs(resolver.resolveType(iface(resolver, "c"), "token"));
	}

	@Test
	void aUseCycleTerminates() {
		// Neither interface DEFINES `token`: the walk has to stop instead of ping-ponging
		// between the two use clauses (a hung compiler, not a failed one).
		WitResolver resolver = resolver("""
				package root:component;

				interface a {
				  use b.{token};
				}

				interface b {
				  use a.{token};
				}
				""");
		assertThat(resolver.resolveType(iface(resolver, "a"), "token")).isNull();
		assertThat(resolver.resolveType(iface(resolver, "b"), "token")).isNull();
	}

	@Test
	void aUseClauseNamingAnInterfaceTheDocumentDoesNotCarryResolvesToNull() {
		WitResolver resolver = resolver("""
				package root:component;

				interface api {
				  use wasi:io/error@0.2.0.{error};

				  fail: func() -> error;
				}
				""");
		assertThat(resolver.resolveType(iface(resolver, "api"), "error")).isNull();
	}

	@Test
	void listsTheFunctionsOfAnInterfaceInDocumentOrderTaggedWithTheirResource() {
		WitResolver resolver = resolver(KEYVALUE);
		List<WitResolver.Func> funcs = WitResolver.functions(iface(resolver, "store"));
		assertThat(described(funcs)).containsExactly("open:PLAIN", "bucket.constructor:CONSTRUCTOR", "bucket.get:PLAIN",
				"bucket.set:PLAIN", "bucket.list-keys:STATIC");
		// The tag is the OWNING resource (null for a freestanding func) -- what a binder
		// needs to name `bucket-get` and to pass the handle as the first argument.
		assertThat(funcs.get(0).resource()).isNull();
		assertThat(funcs.get(2).resource()).isEqualTo("bucket");
		// The WIT parameter names survive: a binder spells the lambda list with them.
		assertThat(funcs.get(2).def().func().params())
			.containsExactly(new WitFunc.Param("key", new WitType.Prim("string")));
	}

	@Test
	void anInterfaceOfTypesOnlyHasNoFunctions() {
		// A record / variant / enum / flags / type alias defines no function, and neither
		// does a bodiless `resource error;` -- the binder reports "declares no
		// functions".
		assertThat(WitResolver.functions(iface(resolver(KEYVALUE), "types"))).isEmpty();
	}

}
