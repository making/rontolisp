package am.ik.wasm;

/**
 * <a href=
 * "https://webassembly.github.io/spec/core/binary/instructions.html">Instructions</a>
 */
public interface Instruction {

	int UNREACHABLE = 0X00;

	int NOP = 0X01;

	int BLOCK = 0X02;

	int LOOP = 0X03;

	int IF = 0X04;

	int ELSE = 0X05;

	int END = 0X0B;

	int BR = 0X0C;

	int BR_IF = 0X0D;

	int BR_TABLE = 0X0E;

	int RETURN = 0X0F;

	int CALL = 0X10;

	int CALL_INDIRECT = 0X11;

	int DROP = 0X1A;

	int SELECT = 0X1B;

	int GET_LOCAL = 0X20;

	int SET_LOCAL = 0X21;

	int TEE_LOCAL = 0X22;

	int GET_GLOBAL = 0X23;

	int SET_GLOBAL = 0X24;

	int I32_LOAD = 0X28;

	int I64_LOAD = 0X29;

	int F32_LOAD = 0X2A;

	int F64_LOAD = 0X2B;

	int I32_LOAD8_S = 0X2C;

	int I32_LOAD8_U = 0X2D;

	int I32_LOAD16_S = 0X2E;

	int I32_LOAD16_U = 0X2F;

	int I64_LOAD8_S = 0X30;

	int I64_LOAD8_U = 0X31;

	int I64_LOAD16_S = 0X32;

	int I64_LOAD16_U = 0X33;

	int I64_LOAD32_S = 0X34;

	int I64_LOAD32_U = 0X35;

	int I32_STORE = 0X36;

	int I64_STORE = 0X37;

	int F32_STORE = 0X38;

	int F64_STORE = 0X39;

	int I32_STORE8 = 0X3A;

	int I32_STORE16 = 0X3B;

	int I64_STORE8 = 0X3C;

	int I64_STORE16 = 0X3D;

	int I64_STORE32 = 0X3E;

	int CURRENT_MEMORY = 0X3F;

	int GROW_MEMORY = 0X40;

	int I32_CONST = 0X41;

	int I64_CONST = 0X42;

	int F32_CONST = 0X43;

	int F64_CONST = 0X44;

	int I32_EQZ = 0X45;

	int I32_EQ = 0X46;

	int I32_NE = 0X47;

	int I32_LT_S = 0X48;

	int I32_LT_U = 0X49;

	int I32_GT_S = 0X4A;

	int I32_GT_U = 0X4B;

	int I32_LE_S = 0X4C;

	int I32_LE_U = 0X4D;

	int I32_GE_S = 0X4E;

	int I32_GE_U = 0X4F;

	int I64_EQZ = 0X50;

	int I64_EQ = 0X51;

	int I64_NE = 0X52;

	int I64_LT_S = 0X53;

	int I64_LT_U = 0X54;

	int I64_GT_S = 0X55;

	int I64_GT_U = 0X56;

	int I64_LE_S = 0X57;

	int I64_LE_U = 0X58;

	int I64_GE_S = 0X59;

	int I64_GE_U = 0X5A;

	int F32_EQ = 0X5B;

	int F32_NE = 0X5C;

	int F32_LT = 0X5D;

	int F32_GT = 0X5E;

	int F32_LE = 0X5F;

	int F32_GE = 0X60;

	int F64_EQ = 0X61;

	int F64_NE = 0X62;

	int F64_LT = 0X63;

	int F64_GT = 0X64;

	int F64_LE = 0X65;

	int F64_GE = 0X66;

	int I32_CLZ = 0X67;

	int I32_CTZ = 0X68;

	int I32_POPCNT = 0X69;

	int I32_ADD = 0X6A;

	int I32_SUB = 0X6B;

	int I32_MUL = 0X6C;

	int I32_DIV_S = 0X6D;

	int I32_DIV_U = 0X6E;

	int I32_REM_S = 0X6F;

	int I32_REM_U = 0X70;

	int I32_AND = 0X71;

	int I32_OR = 0X72;

	int I32_XOR = 0X73;

	int I32_SHL = 0X74;

	int I32_SHR_S = 0X75;

	int I32_SHR_U = 0X76;

	int I32_ROTL = 0X77;

	int I32_ROTR = 0X78;

	int I64_CLZ = 0X79;

	int I64_CTZ = 0X7A;

	int I64_POPCNT = 0X7B;

	int I64_ADD = 0X7C;

	int I64_SUB = 0X7D;

	int I64_MUL = 0X7E;

	int I64_DIV_S = 0X7F;

	int I64_DIV_U = 0X80;

	int I64_REM_S = 0X81;

	int I64_REM_U = 0X82;

	int I64_AND = 0X83;

	int I64_OR = 0X84;

	int I64_XOR = 0X85;

	int I64_SHL = 0X86;

	int I64_SHR_S = 0X87;

	int I64_SHR_U = 0X88;

	int I64_ROTL = 0X89;

	int I64_ROTR = 0X8A;

	int F32_ABS = 0X8B;

	int F32_NEG = 0X8C;

	int F32_CEIL = 0X8D;

	int F32_FLOOR = 0X8E;

	int F32_TRUNC = 0X8F;

	int F32_NEAREST = 0X90;

	int F32_SQRT = 0X91;

	int F32_ADD = 0X92;

	int F32_SUB = 0X93;

	int F32_MUL = 0X94;

	int F32_DIV = 0X95;

	int F32_MIN = 0X96;

	int F32_MAX = 0X97;

	int F32_COPYSIGN = 0X98;

	int F64_ABS = 0X99;

	int F64_NEG = 0X9A;

	int F64_CEIL = 0X9B;

	int F64_FLOOR = 0X9C;

	int F64_TRUNC = 0X9D;

	int F64_NEAREST = 0X9E;

	int F64_SQRT = 0X9F;

	int F64_ADD = 0XA0;

	int F64_SUB = 0XA1;

	int F64_MUL = 0XA2;

	int F64_DIV = 0XA3;

	int F64_MIN = 0XA4;

	int F64_MAX = 0XA5;

	int F64_COPYSIGN = 0XA6;

	int I32_WRAP_I64 = 0XA7;

	int I32_TRUNC_S_F32 = 0XA8;

	int I32_TRUNC_U_F32 = 0XA9;

	int I32_TRUNC_S_F64 = 0XAA;

	int I32_TRUNC_U_F64 = 0XAB;

	int I64_EXTEND_S_I32 = 0XAC;

	int I64_EXTEND_U_I32 = 0XAD;

	int I64_TRUNC_S_F32 = 0XAE;

	int I64_TRUNC_U_F32 = 0XAF;

	int I64_TRUNC_S_F64 = 0XB0;

	int I64_TRUNC_U_F64 = 0XB1;

	int F32_CONVERT_S_I32 = 0XB2;

	int F32_CONVERT_U_I32 = 0XB3;

	int F32_CONVERT_S_I64 = 0XB4;

	int F32_CONVERT_U_I64 = 0XB5;

	int F32_DEMOTE_F64 = 0XB6;

	int F64_CONVERT_S_I32 = 0XB7;

	int F64_CONVERT_U_I32 = 0XB8;

	int F64_CONVERT_S_I64 = 0XB9;

	int F64_CONVERT_U_I64 = 0XBA;

	int F64_PROMOTE_F32 = 0XBB;

	int I32_REINTERPRET_F32 = 0XBC;

	int I64_REINTERPRET_F64 = 0XBD;

	int F32_REINTERPRET_I32 = 0XBE;

	int F64_REINTERPRET_I64 = 0XBF;

	// Reference instructions (no prefix)
	int REF_NULL = 0xD0;

	int REF_IS_NULL = 0xD1;

	int REF_EQ = 0xD3;

	// GC prefix
	int GC_PREFIX = 0xFB;

	// GC instructions (after GC_PREFIX)
	int STRUCT_NEW = 0x00;

	int STRUCT_GET = 0x02;

	int STRUCT_SET = 0x05;

	int ARRAY_NEW = 0x06;

	int ARRAY_GET = 0x0B;

	int ARRAY_SET = 0x0E;

	int ARRAY_LEN = 0x0F;

	int REF_TEST = 0x14;

	int REF_CAST = 0x16;

	int I31_REF_NEW = 0x1C;

	int I31_GET_S = 0x1D;

	int I31_GET_U = 0x1E;

}
