package am.ik.objc;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.PaddingLayout;
import java.lang.foreign.StructLayout;
import java.util.List;

import am.ik.objc.TypeEncoding.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parser that turns what {@code method_getTypeEncoding} answers into a foreign-call
 * shape. Pure, so it runs on every machine; the encodings are the ones the runtime
 * answered on macOS 26.3 (recorded by the spike's {@code Encodings.java}).
 */
class TypeEncodingTest {

	@Test
	void theDesignatedInitializerOfAWindowCarriesAStructByValue() {
		TypeEncoding encoding = TypeEncoding.parse("@68@0:8{CGRect={CGPoint=dd}{CGSize=dd}}16Q48Q56B64");
		assertThat(encoding.returnType().kind()).isEqualTo(Kind.OBJECT);
		assertThat(encoding.argumentTypes()).hasSize(6);
		assertThat(encoding.argumentTypes().get(2).leaves()).containsExactly(Kind.DOUBLE, Kind.DOUBLE, Kind.DOUBLE,
				Kind.DOUBLE);
		assertThat(encoding.argumentTypes().get(3).unsigned()).isTrue();
		assertThat(TypeEncoding.spelling(encoding.descriptor()))
			.isEqualTo("void*(void*,void*,struct(jdouble,jdouble,jdouble,jdouble),jlong,jlong,jboolean)");
	}

	@Test
	void everyScalarKindMapsToItsOwnLayout() {
		TypeEncoding encoding = TypeEncoding.parse("v@0:8c16C17s18S19i20I21l22L23q24Q25f26d27B28*29:30#31^v32");
		assertThat(encoding.returnType().kind()).isEqualTo(Kind.VOID);
		assertThat(encoding.argumentTypes().stream().map(TypeEncoding.Type::kind)).containsExactly(Kind.OBJECT,
				Kind.SELECTOR, Kind.INT8, Kind.INT8, Kind.INT16, Kind.INT16, Kind.INT32, Kind.INT32, Kind.INT64,
				Kind.INT64, Kind.INT64, Kind.INT64, Kind.FLOAT, Kind.DOUBLE, Kind.BOOL, Kind.CSTRING, Kind.SELECTOR,
				Kind.CLASS, Kind.POINTER);
		assertThat(TypeEncoding.spelling(encoding.descriptor())).isEqualTo(
				"void(void*,void*,jbyte,jbyte,jshort,jshort,jint,jint,jlong,jlong,jlong,jlong,jfloat,jdouble,jboolean,"
						+ "void*,void*,void*,void*)");
	}

	@Test
	void aStructReturnIsFlattenedToItsLeaves() {
		TypeEncoding range = TypeEncoding.parse("{_NSRange=QQ}32@0:8@16");
		assertThat(range.returnType().isStruct()).isTrue();
		assertThat(TypeEncoding.spelling(range.descriptor())).isEqualTo("struct(jlong,jlong)(void*,void*,void*)");
		FunctionDescriptor frame = TypeEncoding.parse("{CGRect={CGPoint=dd}{CGSize=dd}}16@0:8").descriptor();
		assertThat(TypeEncoding.spelling(frame)).isEqualTo("struct(jdouble,jdouble,jdouble,jdouble)(void*,void*)");
	}

	@Test
	void aMixedStructGetsItsPaddingAndAnArrayItsRepeats() {
		TypeEncoding.Type mixed = TypeEncoding.parse("v@0:8{?=cd}16").argumentTypes().get(2);
		StructLayout layout = (StructLayout) mixed.layout();
		assertThat(layout).isNotNull();
		assertThat(layout.byteSize()).isEqualTo(16);
		assertThat(layout.memberLayouts().stream().filter(m -> m instanceof PaddingLayout).count()).isEqualTo(1);
		assertThat(TypeEncoding.spelling(FunctionDescriptor.ofVoid(layout))).isEqualTo("void(struct(jbyte,jdouble))");
		TypeEncoding.Type array = TypeEncoding.parse("v@0:8{?=[4d]}16").argumentTypes().get(2);
		assertThat(array.leaves()).containsExactly(Kind.DOUBLE, Kind.DOUBLE, Kind.DOUBLE, Kind.DOUBLE);
	}

	@Test
	void qualifiersAndQuotedNamesAreSkipped() {
		TypeEncoding encoding = TypeEncoding.parse("r*16@0:8@\"NSString\"16^{CGRect=}24");
		assertThat(encoding.returnType().kind()).isEqualTo(Kind.CSTRING);
		assertThat(encoding.argumentTypes().get(2).kind()).isEqualTo(Kind.OBJECT);
		assertThat(encoding.argumentTypes().get(3).kind()).isEqualTo(Kind.POINTER);
	}

	@Test
	void theShapesOutsideTheFirstCutAreRefusedByName() {
		assertThatThrownBy(() -> TypeEncoding.parse("v24@0:8@?16")).isInstanceOf(ObjcException.class)
			.hasMessageContaining("block");
		assertThatThrownBy(() -> TypeEncoding.parse("v24@0:8(?=id)16")).isInstanceOf(ObjcException.class)
			.hasMessageContaining("union");
		assertThatThrownBy(() -> TypeEncoding.parse("v24@0:8b3")).isInstanceOf(ObjcException.class)
			.hasMessageContaining("bitfield");
		assertThatThrownBy(() -> TypeEncoding.parse("v24@0:8?16")).isInstanceOf(ObjcException.class)
			.hasMessageContaining("function pointer");
		assertThatThrownBy(() -> TypeEncoding.parse("")).isInstanceOf(ObjcException.class);
	}

	@Test
	void theSpellingIsTheMetadataFilesOwn() {
		MemoryLayout mtlSize = MemoryLayout.structLayout(java.lang.foreign.ValueLayout.JAVA_LONG,
				java.lang.foreign.ValueLayout.JAVA_LONG, java.lang.foreign.ValueLayout.JAVA_LONG);
		assertThat(TypeEncoding.spelling(FunctionDescriptor.ofVoid(java.lang.foreign.ValueLayout.ADDRESS, mtlSize)))
			.isEqualTo("void(void*,struct(jlong,jlong,jlong))");
		assertThat(TypeEncoding.spelling(FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT)))
			.isEqualTo("jint()");
		assertThat(List.of(TypeEncoding.spelling(FunctionDescriptor.ofVoid()))).containsExactly("void()");
	}

}
