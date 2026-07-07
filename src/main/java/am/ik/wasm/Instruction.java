package am.ik.wasm;

/**
 * WebAssembly instruction opcodes.
 * <p>
 * <a href=
 * "https://webassembly.github.io/spec/core/binary/instructions.html">Instructions</a>
 */
public interface Instruction {

	/** {@code UNREACHABLE} (0x00). */
	int UNREACHABLE = 0X00;

	/** {@code NOP} (0x01). */
	int NOP = 0X01;

	/** {@code BLOCK} (0x02). */
	int BLOCK = 0X02;

	/** {@code LOOP} (0x03). */
	int LOOP = 0X03;

	/** {@code IF} (0x04). */
	int IF = 0X04;

	/** {@code ELSE} (0x05). */
	int ELSE = 0X05;

	/** {@code END} (0x0B). */
	int END = 0X0B;

	/** {@code BR} (0x0C). */
	int BR = 0X0C;

	/** {@code BR_IF} (0x0D). */
	int BR_IF = 0X0D;

	/** {@code BR_TABLE} (0x0E). */
	int BR_TABLE = 0X0E;

	/** {@code RETURN} (0x0F). */
	int RETURN = 0X0F;

	/** {@code CALL} (0x10). */
	int CALL = 0X10;

	/** {@code CALL_INDIRECT} (0x11). */
	int CALL_INDIRECT = 0X11;

	/** {@code DROP} (0x1A). */
	int DROP = 0X1A;

	/** {@code SELECT} (0x1B). */
	int SELECT = 0X1B;

	/** {@code GET_LOCAL} (0x20). */
	int GET_LOCAL = 0X20;

	/** {@code SET_LOCAL} (0x21). */
	int SET_LOCAL = 0X21;

	/** {@code TEE_LOCAL} (0x22). */
	int TEE_LOCAL = 0X22;

	/** {@code GET_GLOBAL} (0x23). */
	int GET_GLOBAL = 0X23;

	/** {@code SET_GLOBAL} (0x24). */
	int SET_GLOBAL = 0X24;

	/** {@code I32_LOAD} (0x28). */
	int I32_LOAD = 0X28;

	/** {@code I64_LOAD} (0x29). */
	int I64_LOAD = 0X29;

	/** {@code F32_LOAD} (0x2A). */
	int F32_LOAD = 0X2A;

	/** {@code F64_LOAD} (0x2B). */
	int F64_LOAD = 0X2B;

	/** {@code I32_LOAD8_S} (0x2C). */
	int I32_LOAD8_S = 0X2C;

	/** {@code I32_LOAD8_U} (0x2D). */
	int I32_LOAD8_U = 0X2D;

	/** {@code I32_LOAD16_S} (0x2E). */
	int I32_LOAD16_S = 0X2E;

	/** {@code I32_LOAD16_U} (0x2F). */
	int I32_LOAD16_U = 0X2F;

	/** {@code I64_LOAD8_S} (0x30). */
	int I64_LOAD8_S = 0X30;

	/** {@code I64_LOAD8_U} (0x31). */
	int I64_LOAD8_U = 0X31;

	/** {@code I64_LOAD16_S} (0x32). */
	int I64_LOAD16_S = 0X32;

	/** {@code I64_LOAD16_U} (0x33). */
	int I64_LOAD16_U = 0X33;

	/** {@code I64_LOAD32_S} (0x34). */
	int I64_LOAD32_S = 0X34;

	/** {@code I64_LOAD32_U} (0x35). */
	int I64_LOAD32_U = 0X35;

	/** {@code I32_STORE} (0x36). */
	int I32_STORE = 0X36;

	/** {@code I64_STORE} (0x37). */
	int I64_STORE = 0X37;

	/** {@code F32_STORE} (0x38). */
	int F32_STORE = 0X38;

	/** {@code F64_STORE} (0x39). */
	int F64_STORE = 0X39;

	/** {@code I32_STORE8} (0x3A). */
	int I32_STORE8 = 0X3A;

	/** {@code I32_STORE16} (0x3B). */
	int I32_STORE16 = 0X3B;

	/** {@code I64_STORE8} (0x3C). */
	int I64_STORE8 = 0X3C;

	/** {@code I64_STORE16} (0x3D). */
	int I64_STORE16 = 0X3D;

	/** {@code I64_STORE32} (0x3E). */
	int I64_STORE32 = 0X3E;

	/** {@code CURRENT_MEMORY} (0x3F). */
	int CURRENT_MEMORY = 0X3F;

	/** {@code GROW_MEMORY} (0x40). */
	int GROW_MEMORY = 0X40;

	/** {@code I32_CONST} (0x41). */
	int I32_CONST = 0X41;

	/** {@code I64_CONST} (0x42). */
	int I64_CONST = 0X42;

	/** {@code F32_CONST} (0x43). */
	int F32_CONST = 0X43;

	/** {@code F64_CONST} (0x44). */
	int F64_CONST = 0X44;

	/** {@code I32_EQZ} (0x45). */
	int I32_EQZ = 0X45;

	/** {@code I32_EQ} (0x46). */
	int I32_EQ = 0X46;

	/** {@code I32_NE} (0x47). */
	int I32_NE = 0X47;

	/** {@code I32_LT_S} (0x48). */
	int I32_LT_S = 0X48;

	/** {@code I32_LT_U} (0x49). */
	int I32_LT_U = 0X49;

	/** {@code I32_GT_S} (0x4A). */
	int I32_GT_S = 0X4A;

	/** {@code I32_GT_U} (0x4B). */
	int I32_GT_U = 0X4B;

	/** {@code I32_LE_S} (0x4C). */
	int I32_LE_S = 0X4C;

	/** {@code I32_LE_U} (0x4D). */
	int I32_LE_U = 0X4D;

	/** {@code I32_GE_S} (0x4E). */
	int I32_GE_S = 0X4E;

	/** {@code I32_GE_U} (0x4F). */
	int I32_GE_U = 0X4F;

	/** {@code I64_EQZ} (0x50). */
	int I64_EQZ = 0X50;

	/** {@code I64_EQ} (0x51). */
	int I64_EQ = 0X51;

	/** {@code I64_NE} (0x52). */
	int I64_NE = 0X52;

	/** {@code I64_LT_S} (0x53). */
	int I64_LT_S = 0X53;

	/** {@code I64_LT_U} (0x54). */
	int I64_LT_U = 0X54;

	/** {@code I64_GT_S} (0x55). */
	int I64_GT_S = 0X55;

	/** {@code I64_GT_U} (0x56). */
	int I64_GT_U = 0X56;

	/** {@code I64_LE_S} (0x57). */
	int I64_LE_S = 0X57;

	/** {@code I64_LE_U} (0x58). */
	int I64_LE_U = 0X58;

	/** {@code I64_GE_S} (0x59). */
	int I64_GE_S = 0X59;

	/** {@code I64_GE_U} (0x5A). */
	int I64_GE_U = 0X5A;

	/** {@code F32_EQ} (0x5B). */
	int F32_EQ = 0X5B;

	/** {@code F32_NE} (0x5C). */
	int F32_NE = 0X5C;

	/** {@code F32_LT} (0x5D). */
	int F32_LT = 0X5D;

	/** {@code F32_GT} (0x5E). */
	int F32_GT = 0X5E;

	/** {@code F32_LE} (0x5F). */
	int F32_LE = 0X5F;

	/** {@code F32_GE} (0x60). */
	int F32_GE = 0X60;

	/** {@code F64_EQ} (0x61). */
	int F64_EQ = 0X61;

	/** {@code F64_NE} (0x62). */
	int F64_NE = 0X62;

	/** {@code F64_LT} (0x63). */
	int F64_LT = 0X63;

	/** {@code F64_GT} (0x64). */
	int F64_GT = 0X64;

	/** {@code F64_LE} (0x65). */
	int F64_LE = 0X65;

	/** {@code F64_GE} (0x66). */
	int F64_GE = 0X66;

	/** {@code I32_CLZ} (0x67). */
	int I32_CLZ = 0X67;

	/** {@code I32_CTZ} (0x68). */
	int I32_CTZ = 0X68;

	/** {@code I32_POPCNT} (0x69). */
	int I32_POPCNT = 0X69;

	/** {@code I32_ADD} (0x6A). */
	int I32_ADD = 0X6A;

	/** {@code I32_SUB} (0x6B). */
	int I32_SUB = 0X6B;

	/** {@code I32_MUL} (0x6C). */
	int I32_MUL = 0X6C;

	/** {@code I32_DIV_S} (0x6D). */
	int I32_DIV_S = 0X6D;

	/** {@code I32_DIV_U} (0x6E). */
	int I32_DIV_U = 0X6E;

	/** {@code I32_REM_S} (0x6F). */
	int I32_REM_S = 0X6F;

	/** {@code I32_REM_U} (0x70). */
	int I32_REM_U = 0X70;

	/** {@code I32_AND} (0x71). */
	int I32_AND = 0X71;

	/** {@code I32_OR} (0x72). */
	int I32_OR = 0X72;

	/** {@code I32_XOR} (0x73). */
	int I32_XOR = 0X73;

	/** {@code I32_SHL} (0x74). */
	int I32_SHL = 0X74;

	/** {@code I32_SHR_S} (0x75). */
	int I32_SHR_S = 0X75;

	/** {@code I32_SHR_U} (0x76). */
	int I32_SHR_U = 0X76;

	/** {@code I32_ROTL} (0x77). */
	int I32_ROTL = 0X77;

	/** {@code I32_ROTR} (0x78). */
	int I32_ROTR = 0X78;

	/** {@code I64_CLZ} (0x79). */
	int I64_CLZ = 0X79;

	/** {@code I64_CTZ} (0x7A). */
	int I64_CTZ = 0X7A;

	/** {@code I64_POPCNT} (0x7B). */
	int I64_POPCNT = 0X7B;

	/** {@code I64_ADD} (0x7C). */
	int I64_ADD = 0X7C;

	/** {@code I64_SUB} (0x7D). */
	int I64_SUB = 0X7D;

	/** {@code I64_MUL} (0x7E). */
	int I64_MUL = 0X7E;

	/** {@code I64_DIV_S} (0x7F). */
	int I64_DIV_S = 0X7F;

	/** {@code I64_DIV_U} (0x80). */
	int I64_DIV_U = 0X80;

	/** {@code I64_REM_S} (0x81). */
	int I64_REM_S = 0X81;

	/** {@code I64_REM_U} (0x82). */
	int I64_REM_U = 0X82;

	/** {@code I64_AND} (0x83). */
	int I64_AND = 0X83;

	/** {@code I64_OR} (0x84). */
	int I64_OR = 0X84;

	/** {@code I64_XOR} (0x85). */
	int I64_XOR = 0X85;

	/** {@code I64_SHL} (0x86). */
	int I64_SHL = 0X86;

	/** {@code I64_SHR_S} (0x87). */
	int I64_SHR_S = 0X87;

	/** {@code I64_SHR_U} (0x88). */
	int I64_SHR_U = 0X88;

	/** {@code I64_ROTL} (0x89). */
	int I64_ROTL = 0X89;

	/** {@code I64_ROTR} (0x8A). */
	int I64_ROTR = 0X8A;

	/** {@code F32_ABS} (0x8B). */
	int F32_ABS = 0X8B;

	/** {@code F32_NEG} (0x8C). */
	int F32_NEG = 0X8C;

	/** {@code F32_CEIL} (0x8D). */
	int F32_CEIL = 0X8D;

	/** {@code F32_FLOOR} (0x8E). */
	int F32_FLOOR = 0X8E;

	/** {@code F32_TRUNC} (0x8F). */
	int F32_TRUNC = 0X8F;

	/** {@code F32_NEAREST} (0x90). */
	int F32_NEAREST = 0X90;

	/** {@code F32_SQRT} (0x91). */
	int F32_SQRT = 0X91;

	/** {@code F32_ADD} (0x92). */
	int F32_ADD = 0X92;

	/** {@code F32_SUB} (0x93). */
	int F32_SUB = 0X93;

	/** {@code F32_MUL} (0x94). */
	int F32_MUL = 0X94;

	/** {@code F32_DIV} (0x95). */
	int F32_DIV = 0X95;

	/** {@code F32_MIN} (0x96). */
	int F32_MIN = 0X96;

	/** {@code F32_MAX} (0x97). */
	int F32_MAX = 0X97;

	/** {@code F32_COPYSIGN} (0x98). */
	int F32_COPYSIGN = 0X98;

	/** {@code F64_ABS} (0x99). */
	int F64_ABS = 0X99;

	/** {@code F64_NEG} (0x9A). */
	int F64_NEG = 0X9A;

	/** {@code F64_CEIL} (0x9B). */
	int F64_CEIL = 0X9B;

	/** {@code F64_FLOOR} (0x9C). */
	int F64_FLOOR = 0X9C;

	/** {@code F64_TRUNC} (0x9D). */
	int F64_TRUNC = 0X9D;

	/** {@code F64_NEAREST} (0x9E). */
	int F64_NEAREST = 0X9E;

	/** {@code F64_SQRT} (0x9F). */
	int F64_SQRT = 0X9F;

	/** {@code F64_ADD} (0xA0). */
	int F64_ADD = 0XA0;

	/** {@code F64_SUB} (0xA1). */
	int F64_SUB = 0XA1;

	/** {@code F64_MUL} (0xA2). */
	int F64_MUL = 0XA2;

	/** {@code F64_DIV} (0xA3). */
	int F64_DIV = 0XA3;

	/** {@code F64_MIN} (0xA4). */
	int F64_MIN = 0XA4;

	/** {@code F64_MAX} (0xA5). */
	int F64_MAX = 0XA5;

	/** {@code F64_COPYSIGN} (0xA6). */
	int F64_COPYSIGN = 0XA6;

	/** {@code I32_WRAP_I64} (0xA7). */
	int I32_WRAP_I64 = 0XA7;

	/** {@code I32_TRUNC_S_F32} (0xA8). */
	int I32_TRUNC_S_F32 = 0XA8;

	/** {@code I32_TRUNC_U_F32} (0xA9). */
	int I32_TRUNC_U_F32 = 0XA9;

	/** {@code I32_TRUNC_S_F64} (0xAA). */
	int I32_TRUNC_S_F64 = 0XAA;

	/** {@code I32_TRUNC_U_F64} (0xAB). */
	int I32_TRUNC_U_F64 = 0XAB;

	/** {@code I64_EXTEND_S_I32} (0xAC). */
	int I64_EXTEND_S_I32 = 0XAC;

	/** {@code I64_EXTEND_U_I32} (0xAD). */
	int I64_EXTEND_U_I32 = 0XAD;

	/** {@code I64_TRUNC_S_F32} (0xAE). */
	int I64_TRUNC_S_F32 = 0XAE;

	/** {@code I64_TRUNC_U_F32} (0xAF). */
	int I64_TRUNC_U_F32 = 0XAF;

	/** {@code I64_TRUNC_S_F64} (0xB0). */
	int I64_TRUNC_S_F64 = 0XB0;

	/** {@code I64_TRUNC_U_F64} (0xB1). */
	int I64_TRUNC_U_F64 = 0XB1;

	/** {@code F32_CONVERT_S_I32} (0xB2). */
	int F32_CONVERT_S_I32 = 0XB2;

	/** {@code F32_CONVERT_U_I32} (0xB3). */
	int F32_CONVERT_U_I32 = 0XB3;

	/** {@code F32_CONVERT_S_I64} (0xB4). */
	int F32_CONVERT_S_I64 = 0XB4;

	/** {@code F32_CONVERT_U_I64} (0xB5). */
	int F32_CONVERT_U_I64 = 0XB5;

	/** {@code F32_DEMOTE_F64} (0xB6). */
	int F32_DEMOTE_F64 = 0XB6;

	/** {@code F64_CONVERT_S_I32} (0xB7). */
	int F64_CONVERT_S_I32 = 0XB7;

	/** {@code F64_CONVERT_U_I32} (0xB8). */
	int F64_CONVERT_U_I32 = 0XB8;

	/** {@code F64_CONVERT_S_I64} (0xB9). */
	int F64_CONVERT_S_I64 = 0XB9;

	/** {@code F64_CONVERT_U_I64} (0xBA). */
	int F64_CONVERT_U_I64 = 0XBA;

	/** {@code F64_PROMOTE_F32} (0xBB). */
	int F64_PROMOTE_F32 = 0XBB;

	/** {@code I32_REINTERPRET_F32} (0xBC). */
	int I32_REINTERPRET_F32 = 0XBC;

	/** {@code I64_REINTERPRET_F64} (0xBD). */
	int I64_REINTERPRET_F64 = 0XBD;

	/** {@code F32_REINTERPRET_I32} (0xBE). */
	int F32_REINTERPRET_I32 = 0XBE;

	/** {@code F64_REINTERPRET_I64} (0xBF). */
	int F64_REINTERPRET_I64 = 0XBF;

	// Reference instructions (no prefix)
	/** {@code REF_NULL} (0xD0). */
	int REF_NULL = 0xD0;

	/** {@code REF_IS_NULL} (0xD1). */
	int REF_IS_NULL = 0xD1;

	/** {@code REF_EQ} (0xD3). */
	int REF_EQ = 0xD3;

	// GC prefix
	/** {@code GC_PREFIX} (0xFB). */
	int GC_PREFIX = 0xFB;

	// GC instructions (after GC_PREFIX)
	/** {@code STRUCT_NEW} (0x00). */
	int STRUCT_NEW = 0x00;

	/** {@code STRUCT_GET} (0x02). */
	int STRUCT_GET = 0x02;

	/** {@code STRUCT_SET} (0x05). */
	int STRUCT_SET = 0x05;

	/** {@code ARRAY_NEW} (0x06). */
	int ARRAY_NEW = 0x06;

	/** {@code ARRAY_NEW_DEFAULT} (0x07). */
	int ARRAY_NEW_DEFAULT = 0x07;

	/** {@code ARRAY_NEW_FIXED} (0x08). */
	int ARRAY_NEW_FIXED = 0x08;

	/** {@code ARRAY_NEW_DATA} (0x09). */
	int ARRAY_NEW_DATA = 0x09;

	/** {@code ARRAY_GET} (0x0B). */
	int ARRAY_GET = 0x0B;

	/** {@code ARRAY_GET_S} (0x0C). */
	int ARRAY_GET_S = 0x0C;

	/** {@code ARRAY_GET_U} (0x0D). */
	int ARRAY_GET_U = 0x0D;

	/** {@code ARRAY_SET} (0x0E). */
	int ARRAY_SET = 0x0E;

	/** {@code ARRAY_LEN} (0x0F). */
	int ARRAY_LEN = 0x0F;

	/** {@code ARRAY_FILL} (0x10). */
	int ARRAY_FILL = 0x10;

	/** {@code ARRAY_COPY} (0x11). */
	int ARRAY_COPY = 0x11;

	/** {@code REF_TEST} (0x14). */
	int REF_TEST = 0x14;

	/** {@code REF_CAST} (0x16). */
	int REF_CAST = 0x16;

	/** {@code I31_REF_NEW} (0x1C). */
	int I31_REF_NEW = 0x1C;

	/** {@code I31_GET_S} (0x1D). */
	int I31_GET_S = 0x1D;

	/** {@code I31_GET_U} (0x1E). */
	int I31_GET_U = 0x1E;

}
