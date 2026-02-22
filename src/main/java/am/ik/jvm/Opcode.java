package am.ik.jvm;

/** JVM bytecode opcodes as defined in the JVM specification. */
public interface Opcode {

	/** {@code NOP} (0x00). */
	int NOP = 0x00;

	/** {@code ACONST_NULL} (0x01). */
	int ACONST_NULL = 0x01;

	/** {@code ICONST_M1} (0x02). */
	int ICONST_M1 = 0x02;

	/** {@code ICONST_0} (0x03). */
	int ICONST_0 = 0x03;

	/** {@code ICONST_1} (0x04). */
	int ICONST_1 = 0x04;

	/** {@code ICONST_2} (0x05). */
	int ICONST_2 = 0x05;

	/** {@code ICONST_3} (0x06). */
	int ICONST_3 = 0x06;

	/** {@code ICONST_4} (0x07). */
	int ICONST_4 = 0x07;

	/** {@code ICONST_5} (0x08). */
	int ICONST_5 = 0x08;

	/** {@code LCONST_0} (0x09). */
	int LCONST_0 = 0x09;

	/** {@code LCONST_1} (0x0a). */
	int LCONST_1 = 0x0a;

	/** {@code FCONST_0} (0x0b). */
	int FCONST_0 = 0x0b;

	/** {@code FCONST_1} (0x0c). */
	int FCONST_1 = 0x0c;

	/** {@code FCONST_2} (0x0d). */
	int FCONST_2 = 0x0d;

	/** {@code DCONST_0} (0x0e). */
	int DCONST_0 = 0x0e;

	/** {@code DCONST_1} (0x0f). */
	int DCONST_1 = 0x0f;

	/** {@code BIPUSH} (0x10). */
	int BIPUSH = 0x10;

	/** {@code SIPUSH} (0x11). */
	int SIPUSH = 0x11;

	/** {@code LDC} (0x12). */
	int LDC = 0x12;

	/** {@code LDC_W} (0x13). */
	int LDC_W = 0x13;

	/** {@code LDC2_W} (0x14). */
	int LDC2_W = 0x14;

	/** {@code ILOAD} (0x15). */
	int ILOAD = 0x15;

	/** {@code LLOAD} (0x16). */
	int LLOAD = 0x16;

	/** {@code FLOAD} (0x17). */
	int FLOAD = 0x17;

	/** {@code DLOAD} (0x18). */
	int DLOAD = 0x18;

	/** {@code ALOAD} (0x19). */
	int ALOAD = 0x19;

	/** {@code ILOAD_0} (0x1a). */
	int ILOAD_0 = 0x1a;

	/** {@code ILOAD_1} (0x1b). */
	int ILOAD_1 = 0x1b;

	/** {@code ILOAD_2} (0x1c). */
	int ILOAD_2 = 0x1c;

	/** {@code ILOAD_3} (0x1d). */
	int ILOAD_3 = 0x1d;

	/** {@code LLOAD_0} (0x1e). */
	int LLOAD_0 = 0x1e;

	/** {@code LLOAD_1} (0x1f). */
	int LLOAD_1 = 0x1f;

	/** {@code LLOAD_2} (0x20). */
	int LLOAD_2 = 0x20;

	/** {@code LLOAD_3} (0x21). */
	int LLOAD_3 = 0x21;

	/** {@code FLOAD_0} (0x22). */
	int FLOAD_0 = 0x22;

	/** {@code FLOAD_1} (0x23). */
	int FLOAD_1 = 0x23;

	/** {@code FLOAD_2} (0x24). */
	int FLOAD_2 = 0x24;

	/** {@code FLOAD_3} (0x25). */
	int FLOAD_3 = 0x25;

	/** {@code DLOAD_0} (0x26). */
	int DLOAD_0 = 0x26;

	/** {@code DLOAD_1} (0x27). */
	int DLOAD_1 = 0x27;

	/** {@code DLOAD_2} (0x28). */
	int DLOAD_2 = 0x28;

	/** {@code DLOAD_3} (0x29). */
	int DLOAD_3 = 0x29;

	/** {@code ALOAD_0} (0x2a). */
	int ALOAD_0 = 0x2a;

	/** {@code ALOAD_1} (0x2b). */
	int ALOAD_1 = 0x2b;

	/** {@code ALOAD_2} (0x2c). */
	int ALOAD_2 = 0x2c;

	/** {@code ALOAD_3} (0x2d). */
	int ALOAD_3 = 0x2d;

	/** {@code IALOAD} (0x2e). */
	int IALOAD = 0x2e;

	/** {@code LALOAD} (0x2f). */
	int LALOAD = 0x2f;

	/** {@code FALOAD} (0x30). */
	int FALOAD = 0x30;

	/** {@code DALOAD} (0x31). */
	int DALOAD = 0x31;

	/** {@code AALOAD} (0x32). */
	int AALOAD = 0x32;

	/** {@code BALOAD} (0x33). */
	int BALOAD = 0x33;

	/** {@code CALOAD} (0x34). */
	int CALOAD = 0x34;

	/** {@code SALOAD} (0x35). */
	int SALOAD = 0x35;

	/** {@code ISTORE} (0x36). */
	int ISTORE = 0x36;

	/** {@code LSTORE} (0x37). */
	int LSTORE = 0x37;

	/** {@code FSTORE} (0x38). */
	int FSTORE = 0x38;

	/** {@code DSTORE} (0x39). */
	int DSTORE = 0x39;

	/** {@code ASTORE} (0x3a). */
	int ASTORE = 0x3a;

	/** {@code ISTORE_0} (0x3b). */
	int ISTORE_0 = 0x3b;

	/** {@code ISTORE_1} (0x3c). */
	int ISTORE_1 = 0x3c;

	/** {@code ISTORE_2} (0x3d). */
	int ISTORE_2 = 0x3d;

	/** {@code ISTORE_3} (0x3e). */
	int ISTORE_3 = 0x3e;

	/** {@code LSTORE_0} (0x3f). */
	int LSTORE_0 = 0x3f;

	/** {@code LSTORE_1} (0x40). */
	int LSTORE_1 = 0x40;

	/** {@code LSTORE_2} (0x41). */
	int LSTORE_2 = 0x41;

	/** {@code LSTORE_3} (0x42). */
	int LSTORE_3 = 0x42;

	/** {@code FSTORE_0} (0x43). */
	int FSTORE_0 = 0x43;

	/** {@code FSTORE_1} (0x44). */
	int FSTORE_1 = 0x44;

	/** {@code FSTORE_2} (0x45). */
	int FSTORE_2 = 0x45;

	/** {@code FSTORE_3} (0x46). */
	int FSTORE_3 = 0x46;

	/** {@code DSTORE_0} (0x47). */
	int DSTORE_0 = 0x47;

	/** {@code DSTORE_1} (0x48). */
	int DSTORE_1 = 0x48;

	/** {@code DSTORE_2} (0x49). */
	int DSTORE_2 = 0x49;

	/** {@code DSTORE_3} (0x4a). */
	int DSTORE_3 = 0x4a;

	/** {@code ASTORE_0} (0x4b). */
	int ASTORE_0 = 0x4b;

	/** {@code ASTORE_1} (0x4c). */
	int ASTORE_1 = 0x4c;

	/** {@code ASTORE_2} (0x4d). */
	int ASTORE_2 = 0x4d;

	/** {@code ASTORE_3} (0x4e). */
	int ASTORE_3 = 0x4e;

	/** {@code IASTORE} (0x4f). */
	int IASTORE = 0x4f;

	/** {@code LASTORE} (0x50). */
	int LASTORE = 0x50;

	/** {@code FASTORE} (0x51). */
	int FASTORE = 0x51;

	/** {@code DASTORE} (0x52). */
	int DASTORE = 0x52;

	/** {@code AASTORE} (0x53). */
	int AASTORE = 0x53;

	/** {@code BASTORE} (0x54). */
	int BASTORE = 0x54;

	/** {@code CASTORE} (0x55). */
	int CASTORE = 0x55;

	/** {@code SASTORE} (0x56). */
	int SASTORE = 0x56;

	/** {@code POP} (0x57). */
	int POP = 0x57;

	/** {@code POP2} (0x58). */
	int POP2 = 0x58;

	/** {@code DUP} (0x59). */
	int DUP = 0x59;

	/** {@code DUP_X1} (0x5a). */
	int DUP_X1 = 0x5a;

	/** {@code DUP_X2} (0x5b). */
	int DUP_X2 = 0x5b;

	/** {@code DUP2} (0x5c). */
	int DUP2 = 0x5c;

	/** {@code DUP2_X1} (0x5d). */
	int DUP2_X1 = 0x5d;

	/** {@code DUP2_X2} (0x5e). */
	int DUP2_X2 = 0x5e;

	/** {@code SWAP} (0x5f). */
	int SWAP = 0x5f;

	/** {@code IADD} (0x60). */
	int IADD = 0x60;

	/** {@code LADD} (0x61). */
	int LADD = 0x61;

	/** {@code FADD} (0x62). */
	int FADD = 0x62;

	/** {@code DADD} (0x63). */
	int DADD = 0x63;

	/** {@code ISUB} (0x64). */
	int ISUB = 0x64;

	/** {@code LSUB} (0x65). */
	int LSUB = 0x65;

	/** {@code FSUB} (0x66). */
	int FSUB = 0x66;

	/** {@code DSUB} (0x67). */
	int DSUB = 0x67;

	/** {@code IMUL} (0x68). */
	int IMUL = 0x68;

	/** {@code LMUL} (0x69). */
	int LMUL = 0x69;

	/** {@code FMUL} (0x6a). */
	int FMUL = 0x6a;

	/** {@code DMUL} (0x6b). */
	int DMUL = 0x6b;

	/** {@code IDIV} (0x6c). */
	int IDIV = 0x6c;

	/** {@code LDIV} (0x6d). */
	int LDIV = 0x6d;

	/** {@code FDIV} (0x6e). */
	int FDIV = 0x6e;

	/** {@code DDIV} (0x6f). */
	int DDIV = 0x6f;

	/** {@code IREM} (0x70). */
	int IREM = 0x70;

	/** {@code LREM} (0x71). */
	int LREM = 0x71;

	/** {@code FREM} (0x72). */
	int FREM = 0x72;

	/** {@code DREM} (0x73). */
	int DREM = 0x73;

	/** {@code INEG} (0x74). */
	int INEG = 0x74;

	/** {@code LNEG} (0x75). */
	int LNEG = 0x75;

	/** {@code FNEG} (0x76). */
	int FNEG = 0x76;

	/** {@code DNEG} (0x77). */
	int DNEG = 0x77;

	/** {@code ISHL} (0x78). */
	int ISHL = 0x78;

	/** {@code LSHL} (0x79). */
	int LSHL = 0x79;

	/** {@code ISHR} (0x7a). */
	int ISHR = 0x7a;

	/** {@code LSHR} (0x7b). */
	int LSHR = 0x7b;

	/** {@code IUSHR} (0x7c). */
	int IUSHR = 0x7c;

	/** {@code LUSHR} (0x7d). */
	int LUSHR = 0x7d;

	/** {@code IAND} (0x7e). */
	int IAND = 0x7e;

	/** {@code LAND} (0x7f). */
	int LAND = 0x7f;

	/** {@code IOR} (0x80). */
	int IOR = 0x80;

	/** {@code LOR} (0x81). */
	int LOR = 0x81;

	/** {@code IXOR} (0x82). */
	int IXOR = 0x82;

	/** {@code LXOR} (0x83). */
	int LXOR = 0x83;

	/** {@code IINC} (0x84). */
	int IINC = 0x84;

	/** {@code I2L} (0x85). */
	int I2L = 0x85;

	/** {@code I2F} (0x86). */
	int I2F = 0x86;

	/** {@code I2D} (0x87). */
	int I2D = 0x87;

	/** {@code L2I} (0x88). */
	int L2I = 0x88;

	/** {@code L2F} (0x89). */
	int L2F = 0x89;

	/** {@code L2D} (0x8a). */
	int L2D = 0x8a;

	/** {@code F2I} (0x8b). */
	int F2I = 0x8b;

	/** {@code F2L} (0x8c). */
	int F2L = 0x8c;

	/** {@code F2D} (0x8d). */
	int F2D = 0x8d;

	/** {@code D2I} (0x8e). */
	int D2I = 0x8e;

	/** {@code D2L} (0x8f). */
	int D2L = 0x8f;

	/** {@code D2F} (0x90). */
	int D2F = 0x90;

	/** {@code I2B} (0x91). */
	int I2B = 0x91;

	/** {@code I2C} (0x92). */
	int I2C = 0x92;

	/** {@code I2S} (0x93). */
	int I2S = 0x93;

	/** {@code LCMP} (0x94). */
	int LCMP = 0x94;

	/** {@code FCMPL} (0x95). */
	int FCMPL = 0x95;

	/** {@code FCMPG} (0x96). */
	int FCMPG = 0x96;

	/** {@code DCMPL} (0x97). */
	int DCMPL = 0x97;

	/** {@code DCMPG} (0x98). */
	int DCMPG = 0x98;

	/** {@code IFEQ} (0x99). */
	int IFEQ = 0x99;

	/** {@code IFNE} (0x9a). */
	int IFNE = 0x9a;

	/** {@code IFLT} (0x9b). */
	int IFLT = 0x9b;

	/** {@code IFGE} (0x9c). */
	int IFGE = 0x9c;

	/** {@code IFGT} (0x9d). */
	int IFGT = 0x9d;

	/** {@code IFLE} (0x9e). */
	int IFLE = 0x9e;

	/** {@code IF_ICMPEQ} (0x9f). */
	int IF_ICMPEQ = 0x9f;

	/** {@code IF_ICMPNE} (0xa0). */
	int IF_ICMPNE = 0xa0;

	/** {@code IF_ICMPLT} (0xa1). */
	int IF_ICMPLT = 0xa1;

	/** {@code IF_ICMPGE} (0xa2). */
	int IF_ICMPGE = 0xa2;

	/** {@code IF_ICMPGT} (0xa3). */
	int IF_ICMPGT = 0xa3;

	/** {@code IF_ICMPLE} (0xa4). */
	int IF_ICMPLE = 0xa4;

	/** {@code IF_ACMPEQ} (0xa5). */
	int IF_ACMPEQ = 0xa5;

	/** {@code IF_ACMPNE} (0xa6). */
	int IF_ACMPNE = 0xa6;

	/** {@code GOTO} (0xa7). */
	int GOTO = 0xa7;

	/** {@code JSR} (0xa8). */
	int JSR = 0xa8;

	/** {@code RET} (0xa9). */
	int RET = 0xa9;

	/** {@code TABLESWITCH} (0xaa). */
	int TABLESWITCH = 0xaa;

	/** {@code LOOKUPSWITCH} (0xab). */
	int LOOKUPSWITCH = 0xab;

	/** {@code IRETURN} (0xac). */
	int IRETURN = 0xac;

	/** {@code LRETURN} (0xad). */
	int LRETURN = 0xad;

	/** {@code FRETURN} (0xae). */
	int FRETURN = 0xae;

	/** {@code DRETURN} (0xaf). */
	int DRETURN = 0xaf;

	/** {@code ARETURN} (0xb0). */
	int ARETURN = 0xb0;

	/** {@code RETURN} (0xb1). */
	int RETURN = 0xb1;

	/** {@code GETSTATIC} (0xb2). */
	int GETSTATIC = 0xb2;

	/** {@code PUTSTATIC} (0xb3). */
	int PUTSTATIC = 0xb3;

	/** {@code GETFIELD} (0xb4). */
	int GETFIELD = 0xb4;

	/** {@code PUTFIELD} (0xb5). */
	int PUTFIELD = 0xb5;

	/** {@code INVOKEVIRTUAL} (0xb6). */
	int INVOKEVIRTUAL = 0xb6;

	/** {@code INVOKESPECIAL} (0xb7). */
	int INVOKESPECIAL = 0xb7;

	/** {@code INVOKESTATIC} (0xb8). */
	int INVOKESTATIC = 0xb8;

	/** {@code INVOKEINTERFACE} (0xb9). */
	int INVOKEINTERFACE = 0xb9;

	/** {@code INVOKEDYNAMIC} (0xba). */
	int INVOKEDYNAMIC = 0xba;

	/** {@code NEW} (0xbb). */
	int NEW = 0xbb;

	/** {@code NEWARRAY} (0xbc). */
	int NEWARRAY = 0xbc;

	/** {@code ANEWARRAY} (0xbd). */
	int ANEWARRAY = 0xbd;

	/** {@code ARRAYLENGTH} (0xbe). */
	int ARRAYLENGTH = 0xbe;

	/** {@code ATHROW} (0xbf). */
	int ATHROW = 0xbf;

	/** {@code CHECKCAST} (0xc0). */
	int CHECKCAST = 0xc0;

	/** {@code INSTANCEOF} (0xc1). */
	int INSTANCEOF = 0xc1;

	/** {@code MONITORENTER} (0xc2). */
	int MONITORENTER = 0xc2;

	/** {@code MONITOREXIT} (0xc3). */
	int MONITOREXIT = 0xc3;

	/** {@code WIDE} (0xc4). */
	int WIDE = 0xc4;

	/** {@code MULTIANEWARRAY} (0xc5). */
	int MULTIANEWARRAY = 0xc5;

	/** {@code IFNULL} (0xc6). */
	int IFNULL = 0xc6;

	/** {@code IFNONNULL} (0xc7). */
	int IFNONNULL = 0xc7;

	/** {@code GOTO_W} (0xc8). */
	int GOTO_W = 0xc8;

	/** {@code JSR_W} (0xc9). */
	int JSR_W = 0xc9;

	/** {@code BREAKPOINT} (0xca). */
	int BREAKPOINT = 0xca;

	/** {@code IMPDEP1} (0xfe). */
	int IMPDEP1 = 0xfe;

	/** {@code IMPDEP2} (0xff). */
	int IMPDEP2 = 0xff;

}
