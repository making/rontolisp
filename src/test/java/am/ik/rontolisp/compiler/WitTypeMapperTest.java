package am.ik.rontolisp.compiler;

import am.ik.wit.WitItem;
import am.ik.wit.WitMeta;
import am.ik.wit.WitType;
import org.junit.jupiter.api.Test;

import static am.ik.wit.Wit.bool;
import static am.ik.wit.Wit.borrow;
import static am.ik.wit.Wit.charType;
import static am.ik.wit.Wit.enumDef;
import static am.ik.wit.Wit.f32;
import static am.ik.wit.Wit.f64;
import static am.ik.wit.Wit.field;
import static am.ik.wit.Wit.flags;
import static am.ik.wit.Wit.future;
import static am.ik.wit.Wit.list;
import static am.ik.wit.Wit.named;
import static am.ik.wit.Wit.option;
import static am.ik.wit.Wit.own;
import static am.ik.wit.Wit.record;
import static am.ik.wit.Wit.resource;
import static am.ik.wit.Wit.result;
import static am.ik.wit.Wit.s16;
import static am.ik.wit.Wit.s32;
import static am.ik.wit.Wit.s64;
import static am.ik.wit.Wit.s8;
import static am.ik.wit.Wit.stream;
import static am.ik.wit.Wit.string;
import static am.ik.wit.Wit.tuple;
import static am.ik.wit.Wit.typeAlias;
import static am.ik.wit.Wit.u16;
import static am.ik.wit.Wit.u32;
import static am.ik.wit.Wit.u64;
import static am.ik.wit.Wit.u8;
import static am.ik.wit.Wit.variant;
import static am.ik.wit.Wit.vcase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the todo-124 type-mapping table (the {@code .kb/wit.md} decision record) so that a
 * change to any settled cell fails loudly — reversing a cell after {@code wit-import} /
 * {@code wit-export} ship would be a breaking change to user programs.
 */
class WitTypeMapperTest {

	@Test
	void scalarsMapPerTheTable() {
		assertThat(WitTypeMapper.rep(s8())).isEqualTo(WitTypeMapper.Rep.INT);
		assertThat(WitTypeMapper.rep(s16())).isEqualTo(WitTypeMapper.Rep.INT);
		assertThat(WitTypeMapper.rep(s32())).isEqualTo(WitTypeMapper.Rep.INT);
		assertThat(WitTypeMapper.rep(u8())).isEqualTo(WitTypeMapper.Rep.INT);
		assertThat(WitTypeMapper.rep(u16())).isEqualTo(WitTypeMapper.Rep.INT);
		assertThat(WitTypeMapper.rep(u32())).isEqualTo(WitTypeMapper.Rep.INT);
		assertThat(WitTypeMapper.rep(s64())).isEqualTo(WitTypeMapper.Rep.BIGNUM_INT);
		assertThat(WitTypeMapper.rep(u64())).isEqualTo(WitTypeMapper.Rep.BIGNUM_INT);
		assertThat(WitTypeMapper.rep(f32())).isEqualTo(WitTypeMapper.Rep.FLOAT);
		assertThat(WitTypeMapper.rep(f64())).isEqualTo(WitTypeMapper.Rep.FLOAT);
		assertThat(WitTypeMapper.rep(bool())).isEqualTo(WitTypeMapper.Rep.BOOLEAN);
		assertThat(WitTypeMapper.rep(string())).isEqualTo(WitTypeMapper.Rep.STRING);
		assertThat(WitTypeMapper.rep(charType())).isEqualTo(WitTypeMapper.Rep.CHARACTER);
	}

	@Test
	void listOfU8IsAByteStringEveryOtherListIsAList() {
		assertThat(WitTypeMapper.rep(list(u8()))).isEqualTo(WitTypeMapper.Rep.BYTE_STRING);
		assertThat(WitTypeMapper.rep(list(u16()))).isEqualTo(WitTypeMapper.Rep.LIST);
		assertThat(WitTypeMapper.rep(list(string()))).isEqualTo(WitTypeMapper.Rep.LIST);
		assertThat(WitTypeMapper.rep(list(named("point")))).isEqualTo(WitTypeMapper.Rep.LIST);
	}

	@Test
	void resultMapsToTheConditionContractRegardlessOfArms() {
		assertThat(WitTypeMapper.rep(result())).isEqualTo(WitTypeMapper.Rep.RESULT);
		assertThat(WitTypeMapper.rep(result(s32()))).isEqualTo(WitTypeMapper.Rep.RESULT);
		assertThat(WitTypeMapper.rep(result(null, named("error-code")))).isEqualTo(WitTypeMapper.Rep.RESULT);
		assertThat(WitTypeMapper.rep(result(option(list(u8())), named("error")))).isEqualTo(WitTypeMapper.Rep.RESULT);
	}

	@Test
	void containersAndHandlesMapPerTheTable() {
		assertThat(WitTypeMapper.rep(option(s32()))).isEqualTo(WitTypeMapper.Rep.NIL_OR_VALUE);
		assertThat(WitTypeMapper.rep(tuple(s32(), string()))).isEqualTo(WitTypeMapper.Rep.TUPLE_LIST);
		assertThat(WitTypeMapper.rep(borrow("descriptor"))).isEqualTo(WitTypeMapper.Rep.HANDLE);
		assertThat(WitTypeMapper.rep(own("tcp-socket"))).isEqualTo(WitTypeMapper.Rep.HANDLE);
	}

	@Test
	void streamAndFutureAreHandleLikeReps() {
		// The async WASI 0.3 value types cross the --component boundary as a bare i32
		// handle read/written through the canonical ABI's async built-ins; the
		// interpreter,
		// the JVM and Preview 1 WASM still reject them (WitImportDirective), but the
		// settled
		// house representation is a dedicated handle rep, no longer UNSUPPORTED.
		assertThat(WitTypeMapper.rep(stream(u8()))).isEqualTo(WitTypeMapper.Rep.STREAM_HANDLE);
		assertThat(WitTypeMapper.rep(stream())).isEqualTo(WitTypeMapper.Rep.STREAM_HANDLE);
		assertThat(WitTypeMapper.rep(future(result(null, named("error-code")))))
			.isEqualTo(WitTypeMapper.Rep.FUTURE_HANDLE);
		assertThat(WitTypeMapper.rep(future())).isEqualTo(WitTypeMapper.Rep.FUTURE_HANDLE);
	}

	@Test
	void namedReferencesMustBeResolvedByTheCaller() {
		assertThatThrownBy(() -> WitTypeMapper.rep(named("error-code"))).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("resolved");
	}

	@Test
	void definitionsMapPerTheTable() {
		assertThat(WitTypeMapper.repOfDefinition(record("instant", field("seconds", s64()))))
			.isEqualTo(WitTypeMapper.Rep.PLIST);
		assertThat(WitTypeMapper.repOfDefinition(enumDef("error-code", "io", "pipe")))
			.isEqualTo(WitTypeMapper.Rep.KEYWORD);
		assertThat(WitTypeMapper.repOfDefinition(variant("method", vcase("get"), vcase("other", string()))))
			.isEqualTo(WitTypeMapper.Rep.TAGGED_LIST);
		assertThat(WitTypeMapper.repOfDefinition(flags("path-flags", "symlink-follow")))
			.isEqualTo(WitTypeMapper.Rep.KEYWORD_LIST);
		assertThat(WitTypeMapper.repOfDefinition(resource("descriptor"))).isEqualTo(WitTypeMapper.Rep.HANDLE);
		assertThat(WitTypeMapper.repOfDefinition(typeAlias("filesize", u64()))).isEqualTo(WitTypeMapper.Rep.BIGNUM_INT);
	}

	@Test
	void nonDefinitionsAreRejected() {
		WitItem use = new WitItem.Use(WitMeta.none(), am.ik.wit.WitRef.local("types"), java.util.List.of());
		assertThatThrownBy(() -> WitTypeMapper.repOfDefinition(use)).isInstanceOf(IllegalArgumentException.class);
	}

}
