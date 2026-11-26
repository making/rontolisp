package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;

/**
 * Builds the {@code _localTimeOffset} runtime helper behind the internal
 * {@code %local-time-offset} primitive: the JVM's default zone
 * ({@code ZoneId.systemDefault()}, which honors {@code TZ} and {@code -Duser.timezone})
 * at a Unix instant, as a boxed {@code Long} in the primitive's encoding -- the total
 * offset east of UTC in seconds, doubled, plus 1 under daylight saving time. The same
 * three calls the interpreter makes ({@code eval/LocalTimeZone}).
 *
 * <p>
 * Emitted ONLY when the program references the primitive (the prelude's
 * {@code %local-zone} does, for {@code decode-universal-time},
 * {@code encode-universal-time} and {@code get-decoded-time}), so every other program
 * keeps byte-identical output and never loads {@code java.time}.
 */
final class JvmLocalTimeRuntimeBuilder {

	static final String METHOD = "_localTimeOffset";

	static final String DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

	/** The emitted helper: its name/descriptor plus the body. */
	record LocalTimeRuntime(Utf8Entry name, Utf8Entry desc, MethodCode code) {
	}

	private JvmLocalTimeRuntimeBuilder() {
	}

	static LocalTimeRuntime build(ConstantPool cp, MethodRefEntry longValueOf) {
		ClassEntry number = cp.classEntry("java/lang/Number");
		ClassEntry instant = cp.classEntry("java/time/Instant");
		ClassEntry zoneId = cp.classEntry("java/time/ZoneId");
		ClassEntry zoneRules = cp.classEntry("java/time/zone/ZoneRules");
		ClassEntry zoneOffset = cp.classEntry("java/time/ZoneOffset");
		// Slots: 0=seconds (Object), 1=instant, 2=rules. Straight-line code: the
		// daylight flag is added as the boolean's 0/1, so no frame is needed.
		MethodCode code = new MethodCode();
		code.aload(0);
		code.checkcast(number);
		code.invokevirtual(cp.methodRef(number, "longValue", "()J"));
		code.invokestatic(cp.methodRef(instant, "ofEpochSecond", "(J)Ljava/time/Instant;"));
		code.astore(1);
		code.invokestatic(cp.methodRef(zoneId, "systemDefault", "()Ljava/time/ZoneId;"));
		code.invokevirtual(cp.methodRef(zoneId, "getRules", "()Ljava/time/zone/ZoneRules;"));
		code.astore(2);
		// offset * 2
		code.aload(2);
		code.aload(1);
		code.invokevirtual(cp.methodRef(zoneRules, "getOffset", "(Ljava/time/Instant;)Ljava/time/ZoneOffset;"));
		code.invokevirtual(cp.methodRef(zoneOffset, "getTotalSeconds", "()I"));
		code.loadConstant(1);
		code.ishl();
		// + daylight
		code.aload(2);
		code.aload(1);
		code.invokevirtual(cp.methodRef(zoneRules, "isDaylightSavings", "(Ljava/time/Instant;)Z"));
		code.iadd();
		code.i2l();
		code.invokestatic(longValueOf);
		code.areturn();
		return new LocalTimeRuntime(cp.utf8Entry(METHOD), cp.utf8Entry(DESC), code);
	}

}
