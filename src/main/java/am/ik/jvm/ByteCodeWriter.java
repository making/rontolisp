package am.ik.jvm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.Constant;

/**
 * Writer for JVM class file bytecode.
 */
public class ByteCodeWriter {

	private final OutputStream out;

	/**
	 * Create a new writer that writes to the given output stream.
	 * @param out the target output stream
	 */
	public ByteCodeWriter(OutputStream out) {
		this.out = out;
	}

	/**
	 * Write a 4-byte unsigned integer.
	 * @param n the value to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeU4(int n) {
		try {
			this.out.write(ByteBuffer.allocate(4).putInt(n).array());
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return this;
	}

	/**
	 * Write a 2-byte unsigned integer from a short value.
	 * @param n the value to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeU2(short n) {
		try {
			this.out.write(ByteBuffer.allocate(2).putShort(n).array());
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return this;
	}

	/**
	 * Write a 2-byte unsigned integer from an int value.
	 * @param n the value to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeU2(int n) {
		return this.writeU2((short) n);
	}

	/**
	 * Write a 2-byte unsigned integer from a constant pool entry index.
	 * @param c the constant whose index is written
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeU2(Constant c) {
		return this.writeU2(c.index());
	}

	/**
	 * Write a {@code CONSTANT_Utf8} string with its length prefix. The class-file format
	 * uses "modified UTF-8": the u2 length counts <em>bytes</em>, {@code U+0000} is
	 * encoded as the two-byte sequence {@code 0xC0 0x80}, and a supplementary character
	 * is encoded as its CESU-8 surrogate pair -- encoding each UTF-16 char independently
	 * produces exactly that.
	 * @param s the string to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeUtf8Info(String s) {
		final ByteArrayOutputStream buf = new ByteArrayOutputStream(s.length());
		for (int i = 0; i < s.length(); i++) {
			final char c = s.charAt(i);
			if (c >= 0x0001 && c <= 0x007F) {
				buf.write(c);
			}
			else if (c <= 0x07FF) { // includes U+0000
				buf.write(0xC0 | ((c >> 6) & 0x1F));
				buf.write(0x80 | (c & 0x3F));
			}
			else {
				buf.write(0xE0 | ((c >> 12) & 0x0F));
				buf.write(0x80 | ((c >> 6) & 0x3F));
				buf.write(0x80 | (c & 0x3F));
			}
		}
		final byte[] bytes = buf.toByteArray();
		if (bytes.length > 0xFFFF) {
			throw new IllegalArgumentException("CONSTANT_Utf8 exceeds 65535 bytes: " + bytes.length);
		}
		try {
			this.writeU2(bytes.length);
			out.write(bytes);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return this;
	}

	/**
	 * Write a code attribute body with its length prefix.
	 * @param code the bytecode instructions to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeCode(Object... code) {
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		new ByteCodeWriter(stream).write(code);
		final byte[] bytes = stream.toByteArray();
		if (bytes.length > 0xFFFF) {
			// JVMS 4.7.3: code_length must be less than 65536. Writing a longer body
			// produces a class every JVM rejects at load time with a message that no
			// longer names the culprit; fail here instead.
			throw new IllegalArgumentException("method code exceeds the JVM's 65535-byte limit: " + bytes.length);
		}
		return this.writeU4(bytes.length).write(bytes);
	}

	/**
	 * Write a {@code Code} attribute exception table: the u2 entry count followed by one
	 * {@code start_pc}/{@code end_pc}/{@code handler_pc}/{@code catch_type} u2 quadruple
	 * per entry. Call this between the code array and the attributes count; an empty list
	 * writes the count 0 (a method with no handlers).
	 * @param entries the exception table entries in dispatch order
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeExceptionTable(List<ExceptionTableEntry> entries) {
		this.writeU2(entries.size());
		for (ExceptionTableEntry entry : entries) {
			this.writeU2(entry.startPc()).writeU2(entry.endPc()).writeU2(entry.handlerPc()).writeU2(entry.catchType());
		}
		return this;
	}

	/**
	 * A {@code Code} attribute exception table entry. An exception thrown while the pc is
	 * in {@code [startPc, endPc)} is dispatched to {@code handlerPc} when its class is (a
	 * subclass of) the {@code catchType} class constant; a {@code catchType} of 0 catches
	 * any throwable (the {@code finally} shape).
	 *
	 * @param startPc the inclusive start of the protected code range
	 * @param endPc the exclusive end of the protected code range
	 * @param handlerPc the handler entry point (the operand stack there holds only the
	 * thrown exception)
	 * @param catchType the {@code CONSTANT_Class} pool index of the caught type, or 0 for
	 * any
	 */
	public record ExceptionTableEntry(int startPc, int endPc, int handlerPc, int catchType) {
	}

	/**
	 * Write raw bytes to the output.
	 * @param bytes the bytes to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter write(byte[] bytes) {
		try {
			out.write(bytes);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return this;
	}

	/**
	 * Write a sequence of objects (Integer, Byte, byte[], or String) to the output.
	 * @param objects the objects to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter write(Object... objects) {
		try {
			for (Object o : objects) {
				if (o instanceof Integer) {
					out.write((Integer) o);
				}
				else if (o instanceof Byte) {
					out.write((Byte) o);
				}
				else if (o instanceof byte[]) {
					out.write((byte[]) o);
				}
				else if (o instanceof String) {
					out.write(((String) o).getBytes(StandardCharsets.UTF_8));
				}
				else {
					throw new IllegalStateException(o.getClass() + " is not supported");
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return this;
	}

	/**
	 * Write the class file version (minor and major).
	 * @param minorVersion the minor version number
	 * @param majorVersion the major version number
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeVersion(int minorVersion, int majorVersion) {
		return this.writeU2(minorVersion).writeU2(majorVersion);
	}

	/**
	 * Write a constant pool to the output.
	 * @param constantPool the constant pool to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeConstantPool(ConstantPool constantPool) {
		return this.write(constantPool.toByteArray());
	}

	/**
	 * Write a constant pool built by the given consumer.
	 * @param consumer a consumer that populates the constant pool
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeConstantPool(Consumer<ConstantPool> consumer) {
		final ConstantPool constantPool = new ConstantPool();
		consumer.accept(constantPool);
		return this.writeConstantPool(constantPool);
	}

	/**
	 * Write the class declaration (access flags, this class, super class).
	 * @param accessFlag the access flags
	 * @param thisClass the class constant for this class
	 * @param superClass the class constant for the super class
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeClass(int accessFlag, ClassConstant thisClass, ClassConstant superClass) {
		return this.writeU2(accessFlag).writeU2(thisClass).writeU2(superClass);
	}

	/**
	 * Write the interfaces section.
	 * @param consumer a consumer that populates the interfaces
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeInterfaces(Consumer<CountingDef<?>> consumer) {
		final CountingDef<?> def = new CountingDef<>();
		consumer.accept(def);
		return this.write(def.toByteArray());
	}

	/**
	 * Write the fields section.
	 * @param consumer a consumer that populates the fields
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeFields(Consumer<CountingDef<?>> consumer) {
		final CountingDef<?> def = new CountingDef<>();
		consumer.accept(def);
		return this.write(def.toByteArray());
	}

	/**
	 * Write the methods section from a methods definition.
	 * @param methodsDef the methods definition to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeMethods(MethodsDef methodsDef) {
		return this.write(methodsDef.toByteArray());
	}

	/**
	 * Write the methods section built by the given consumer.
	 * @param consumer a consumer that populates the methods definition
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeMethods(Consumer<MethodsDef> consumer) {
		final MethodsDef methodsDef = new MethodsDef();
		consumer.accept(methodsDef);
		return this.writeMethods(methodsDef);
	}

	/**
	 * Write the attributes section from an attributes definition.
	 * @param attributesDef the attributes definition to write
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeAttributes(AttributesDef attributesDef) {
		return this.write(attributesDef.toByteArray());
	}

	/**
	 * Write the attributes section built by the given consumer.
	 * @param consumer a consumer that populates the attributes definition
	 * @return this instance for chaining
	 */
	public ByteCodeWriter writeAttributes(Consumer<AttributesDef> consumer) {
		final AttributesDef attributesDef = new AttributesDef();
		consumer.accept(attributesDef);
		return this.writeAttributes(attributesDef);
	}

}
