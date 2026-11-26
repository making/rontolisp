package am.ik.rontolisp.codegen.wasm;

import java.util.List;
import java.util.Objects;

import am.ik.wasm.Type;
import am.ik.wit.WitItem;
import am.ik.wit.WitParser;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the canonical-ABI layout facts of {@link WitCanonicalAbi} against the upstream
 * {@code wasi:keyvalue/store@0.2.0-draft} interface. Every expectation here was validated
 * end-to-end against wasmtime 46's real {@code -S keyvalue=y} host: a hand-written core
 * module using exactly these flat signatures and load offsets, wrapped by
 * {@code wasm-tools component new}, linked and answered the expected values (the
 * reference probe).
 *
 * <p>
 * The second fixture pins the other half of the contract: a type only means something
 * together with the interface SCOPE its names resolve in, and that scope changes the
 * moment a walk follows a {@code use} clause.
 */
class WitCanonicalAbiTest {

	private static final String STORE_WIT = """
			package wasi:keyvalue@0.2.0-draft;

			interface store {
			    variant error {
			        no-such-store,
			        access-denied,
			        other(string)
			    }
			    record key-response {
			        keys: list<string>,
			        cursor: option<u64>
			    }
			    open: func(identifier: string) -> result<bucket, error>;
			    resource bucket {
			        get: func(key: string) -> result<option<list<u8>>, error>;
			        set: func(key: string, value: list<u8>) -> result<_, error>;
			        delete: func(key: string) -> result<_, error>;
			        exists: func(key: string) -> result<bool, error>;
			        list-keys: func(cursor: option<u64>) -> result<key-response, error>;
			    }
			}
			""";

	// wasi:http in miniature, and the exact shape that forced the scope threading:
	// `outgoing-handler` uses `error-code` from `types`, and `error-code`'s `DNS-error`
	// case
	// carries a `DNS-error-payload` record that `outgoing-handler` never imported and
	// cannot
	// see. Laying out that payload in the scope the walk STARTED in ("what does
	// DNS-error-payload mean in outgoing-handler?") has no answer at all.
	private static final String HTTP_WIT = """
			package wasi:http@0.2.0;

			interface types {
			    record DNS-error-payload {
			        rcode: option<string>,
			        info-code: option<u16>
			    }
			    variant error-code {
			        DNS-timeout,
			        DNS-error(DNS-error-payload),
			        connection-refused,
			        internal-error(option<string>)
			    }
			    resource outgoing-request;
			    resource future-incoming-response;
			}

			interface outgoing-handler {
			    use types.{outgoing-request, future-incoming-response, error-code};

			    handle: func(request: outgoing-request) -> result<future-incoming-response, error-code>;
			}
			""";

	// ONE resolver over ONE document: a calculator caches its siblings by interface
	// identity, so an interface re-parsed from a second document would not be the same
	// scope.
	private static final WitResolver HTTP = new WitResolver(WitParser.parse(HTTP_WIT));

	private static WitItem.InterfaceDef httpIface(String reference) {
		return Objects.requireNonNull(HTTP.findInterface(reference), reference);
	}

	private static WitCanonicalAbi abi() {
		WitResolver resolver = new WitResolver(WitParser.parse(STORE_WIT));
		WitItem.InterfaceDef iface = Objects.requireNonNull(resolver.findInterface("wasi:keyvalue/store@0.2.0-draft"));
		return new WitCanonicalAbi(resolver, iface);
	}

	private static WitResolver.Func func(String member) {
		WitResolver resolver = new WitResolver(WitParser.parse(STORE_WIT));
		WitItem.InterfaceDef iface = Objects.requireNonNull(resolver.findInterface("wasi:keyvalue/store@0.2.0-draft"));
		return WitResolver.functions(iface)
			.stream()
			.filter(f -> member.equals(am.ik.rontolisp.compiler.WitImportDirective.memberName(f)))
			.findFirst()
			.orElseThrow();
	}

	@Test
	void flatSignaturesMatchTheWasmtimeValidatedProbe() {
		WitCanonicalAbi abi = abi();
		// open(string) -> result<bucket, error>: (ptr, len, retptr) -> (), area 16 bytes
		WitCanonicalAbi.FlatSig open = abi.flatSig(func("open"));
		assertThat(open.params()).containsExactly(Type.I32, Type.I32, Type.I32);
		assertThat(open.results()).isEmpty();
		assertThat(open.retptr()).isTrue();
		assertThat(open.retSize()).isEqualTo(16);
		// get(self, key) -> result<option<list<u8>>, error>: (self, ptr, len, retptr)
		WitCanonicalAbi.FlatSig get = abi.flatSig(func("bucket-get"));
		assertThat(get.params()).containsExactly(Type.I32, Type.I32, Type.I32, Type.I32);
		assertThat(get.retSize()).isEqualTo(16);
		// set(self, key, value) -> result<_, error>
		WitCanonicalAbi.FlatSig set = abi.flatSig(func("bucket-set"));
		assertThat(set.params()).containsExactly(Type.I32, Type.I32, Type.I32, Type.I32, Type.I32, Type.I32);
		assertThat(set.retSize()).isEqualTo(16);
		// exists(self, key) -> result<bool, error>
		assertThat(abi.flatSig(func("bucket-exists")).retSize()).isEqualTo(16);
		// list-keys(self, cursor: option<u64>) -> result<key-response, error>:
		// (self, cursor-disc i32, cursor-value i64, retptr) -> (), area 32 bytes
		WitCanonicalAbi.FlatSig listKeys = abi.flatSig(func("bucket-list-keys"));
		assertThat(listKeys.params()).containsExactly(Type.I32, Type.I32, Type.I64, Type.I32);
		assertThat(listKeys.retptr()).isTrue();
		assertThat(listKeys.retSize()).isEqualTo(32);
	}

	@Test
	void variantAndRecordLayoutsMatchTheProbeOffsets() {
		WitCanonicalAbi abi = abi();
		var getResult = Objects.requireNonNull(func("bucket-get").def().func().result());
		// result disc byte at 0; both payload arms start at offset 4.
		WitCanonicalAbi.VariantInfo result = abi.variantInfo(getResult);
		assertThat(result.discSize()).isEqualTo(1);
		assertThat(result.payloadOffset()).isEqualTo(4);
		assertThat(result.names()).containsExactly("ok", "error");
		// The ok arm option<list<u8>>: disc at +0, the (ptr, len) pair at +4 -- so the
		// probe read the value length at retptr + 4 + 8 = 12 and it answered 5.
		WitCanonicalAbi.VariantInfo option = abi.variantInfo(Objects.requireNonNull(result.payloads().get(0)));
		assertThat(option.discSize()).isEqualTo(1);
		assertThat(option.payloadOffset()).isEqualTo(4);
		// list-keys' ok arm key-response: keys list at 0, cursor option<u64> at 8
		var listKeysResult = Objects.requireNonNull(func("bucket-list-keys").def().func().result());
		WitCanonicalAbi.VariantInfo lk = abi.variantInfo(listKeysResult);
		assertThat(lk.payloadOffset()).isEqualTo(8);
		WitCanonicalAbi.RecordInfo keyResponse = abi.recordInfo(Objects.requireNonNull(lk.payloads().get(0)));
		assertThat(keyResponse.names()).containsExactly("keys", "cursor");
		assertThat(keyResponse.offsets()).containsExactly(0, 8);
		assertThat(abi.size(Objects.requireNonNull(lk.payloads().get(0)))).isEqualTo(24);
		// The error variant: disc byte at 0, the other(string) payload at 4.
		WitCanonicalAbi.VariantInfo error = abi.variantInfo(Objects.requireNonNull(result.payloads().get(1)));
		assertThat(error.names()).containsExactly("no-such-store", "access-denied", "other");
		assertThat(error.payloadOffset()).isEqualTo(4);
	}

	@Test
	void laysOutATypeReachedThroughAUseClauseInTheInterfaceThatDEFINESIt() {
		// The calculator is scoped to `outgoing-handler`, which knows `error-code` only
		// as a
		// name its use clause imported. Laying it out means descending into cases it
		// never
		// wrote, whose own type references (`DNS-error-payload`) belong to `types` -- so
		// the
		// walk has to CONTINUE in the owner's scope. Before the scope threading,
		// computing
		// any of this from here threw "WIT type 'DNS-error-payload' is not defined in
		// interface 'outgoing-handler'".
		WitCanonicalAbi handler = new WitCanonicalAbi(HTTP, httpIface("outgoing-handler"));
		WitCanonicalAbi types = handler.scopedTo(httpIface("types"));
		assertThat(types).isNotSameAs(handler);

		// handle(request) -> result<future-incoming-response, error-code>: the result
		// flattens to 7 core values (a disc + the widest arm, error-code's 6), well past
		// the
		// canonical ABI's one flat result, so it comes back through a return pointer --
		// and
		// the size of that return area is only computable in the owner's scope.
		WitResolver.Func handle = WitResolver.functions(httpIface("outgoing-handler"))
			.stream()
			.filter(f -> "handle".equals(f.def().name()))
			.findFirst()
			.orElseThrow();
		WitCanonicalAbi.FlatSig sig = handler.flatSig(handle);
		assertThat(sig.params()).containsExactly(Type.I32, Type.I32); // the handle, the
																		// retptr
		assertThat(sig.results()).isEmpty();
		assertThat(sig.retptr()).isTrue();
		assertThat(sig.retSize()).isEqualTo(24);

		// The result is written in `outgoing-handler`'s signature, so ITS scope is the
		// one
		// the walk starts in...
		WitType handleResult = Objects.requireNonNull(handle.def().func().result());
		WitCanonicalAbi.VariantInfo result = handler.variantInfo(handleResult);
		assertThat(result.abi()).isSameAs(handler);
		assertThat(result.names()).containsExactly("ok", "error");
		assertThat(result.discSize()).isEqualTo(1);
		assertThat(result.payloadOffset()).isEqualTo(4);

		// ... and the error arm hands the walk over to `types`: the cases it reports are
		// written there, so VariantInfo carries THAT scope, not the one variantInfo was
		// called on.
		WitType errorCodeType = Objects.requireNonNull(result.payloads().get(1));
		WitCanonicalAbi.VariantInfo errorCode = handler.variantInfo(errorCodeType);
		assertThat(errorCode.abi()).isSameAs(types).isNotSameAs(handler);
		assertThat(errorCode.names()).containsExactly("DNS-timeout", "DNS-error", "connection-refused",
				"internal-error");
		assertThat(errorCode.discSize()).isEqualTo(1);
		assertThat(errorCode.payloadOffset()).isEqualTo(4);
		// Its layout facts, computed from the handler's scope through the owner's:
		// 4 (payload offset) + 16 (the widest case, DNS-error-payload), 4-aligned.
		assertThat(handler.size(errorCodeType)).isEqualTo(20);
		assertThat(handler.alignment(errorCodeType)).isEqualTo(4);
		assertThat(handler.flatTypes(errorCodeType)).containsExactly(Type.I32, Type.I32, Type.I32, Type.I32, Type.I32,
				Type.I32);

		// The foreign record payload: rcode option<string> at 0 (disc + ptr/len = 12
		// bytes),
		// info-code option<u16> at 12 (disc + u16 = 4 bytes), so 16 bytes, 4-aligned.
		WitType payloadType = Objects.requireNonNull(errorCode.payloads().get(1));
		WitCanonicalAbi.RecordInfo payload = errorCode.abi().recordInfo(payloadType);
		assertThat(payload.abi()).isSameAs(types);
		assertThat(payload.names()).containsExactly("rcode", "info-code");
		assertThat(payload.offsets()).containsExactly(0, 12);
		assertThat(types.size(payloadType)).isEqualTo(16);
		assertThat(types.alignment(payloadType)).isEqualTo(4);
		assertThat(types.flatTypes(payloadType)).containsExactly(Type.I32, Type.I32, Type.I32, Type.I32, Type.I32);

		// And the scope really is load-bearing rather than decorative: the same payload
		// type
		// asked of the STARTING scope is a name `outgoing-handler` has never heard of.
		// This
		// is what a consumer that kept walking with the abi it started with would hit --
		// so
		// VariantInfo/RecordInfo hand their scope back along with the types.
		assertThatThrownBy(() -> handler.recordInfo(payloadType)).isInstanceOf(UnsupportedOperationException.class)
			.hasMessage("WIT type 'DNS-error-payload' is not defined in interface 'outgoing-handler' (nor imported "
					+ "with a use clause)");
	}

	// A miniature WASI 0.3 async interface: a stream<u8> body, a future<result<...>>
	// completion, and a named alias to a stream -- the shapes wasi:http@0.3 uses.
	private static final String ASYNC_WIT = """
			package test:async@0.3.0;

			interface streaming {
			  enum error-code { failed }
			  type input-stream = stream<u8>;
			  read-body: func(body: stream<u8>) -> future<result<_, error-code>>;
			  echo: func(src: input-stream) -> stream<u8>;
			}
			""";

	private static WitResolver.Func asyncFunc(WitItem.InterfaceDef iface, String witName) {
		return WitResolver.functions(iface)
			.stream()
			.filter(f -> witName.equals(f.def().name()))
			.findFirst()
			.orElseThrow();
	}

	@Test
	void streamAndFutureCrossAsABareI32Handle() {
		WitResolver resolver = new WitResolver(WitParser.parse(ASYNC_WIT));
		WitItem.InterfaceDef iface = Objects.requireNonNull(resolver.findInterface("test:async/streaming@0.3.0"));
		WitCanonicalAbi abi = new WitCanonicalAbi(resolver, iface);

		// A stream<u8> and a future<...> each lay out as one i32 handle: 4 bytes,
		// 4-aligned,
		// flattening to a single i32 -- exactly like a resource handle. The element type
		// governs the async read/write marshalling, not the handle's footprint.
		WitType stream = new WitType.StreamOf(new WitType.Prim("u8"));
		WitType future = new WitType.FutureOf(new WitType.ResultOf(null, new WitType.Named("error-code")));
		assertThat(abi.size(stream)).isEqualTo(4);
		assertThat(abi.alignment(stream)).isEqualTo(4);
		assertThat(abi.flatTypes(stream)).containsExactly(Type.I32);
		assertThat(abi.size(future)).isEqualTo(4);
		assertThat(abi.alignment(future)).isEqualTo(4);
		// The future arm does NOT descend into its result<...> payload (that governs
		// future.read, not the handle layout) -- it terminates at one i32.
		assertThat(abi.flatTypes(future)).containsExactly(Type.I32);

		// read-body(body: stream<u8>) -> future<result<_, error-code>>: the param and the
		// single-i32 result each flatten to one core value, so the result comes back
		// directly with no return pointer.
		WitCanonicalAbi.FlatSig readBody = abi.flatSig(asyncFunc(iface, "read-body"));
		assertThat(readBody.params()).containsExactly(Type.I32);
		assertThat(readBody.results()).containsExactly(Type.I32);
		assertThat(readBody.retptr()).isFalse();

		// A named alias `type input-stream = stream<u8>` resolves through to the same
		// leaf
		// arm, so echo's stream param is one i32 too.
		assertThat(abi.flatSig(asyncFunc(iface, "echo")).params()).containsExactly(Type.I32);
	}

	@Test
	void optionWrappingAStreamRecursesIntoTheHandleArm() {
		WitCanonicalAbi abi = abi();
		// option<stream<u8>> = variant { none, some(stream<u8>) }: a discriminant plus a
		// 4-byte handle payload at offset 4, 4-aligned -> size 8, flattening (disc,
		// handle).
		WitType optStream = new WitType.OptionOf(new WitType.StreamOf(new WitType.Prim("u8")));
		assertThat(abi.size(optStream)).isEqualTo(8);
		assertThat(abi.alignment(optStream)).isEqualTo(4);
		assertThat(abi.flatTypes(optStream)).containsExactly(Type.I32, Type.I32);
	}

}
