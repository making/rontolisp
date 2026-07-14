package am.ik.rontolisp.codegen.wasm;

import java.util.List;
import java.util.Objects;

import am.ik.wasm.Type;
import am.ik.wit.WitItem;
import am.ik.wit.WitParser;
import am.ik.wit.WitResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the canonical-ABI layout facts of {@link WitCanonicalAbi} against the upstream
 * {@code wasi:keyvalue/store@0.2.0-draft} interface. Every expectation here was validated
 * end-to-end against wasmtime 46's real {@code -S keyvalue=y} host: a hand-written core
 * module using exactly these flat signatures and load offsets, wrapped by
 * {@code wasm-tools component new}, linked and answered the expected values (the
 * reference probe).
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

}
