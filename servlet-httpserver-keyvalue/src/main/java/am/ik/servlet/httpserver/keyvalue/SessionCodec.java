package am.ik.servlet.httpserver.keyvalue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.servlet.httpserver.SessionData;
import org.jspecify.annotations.Nullable;

/**
 * A session as the octets of a value: a version, its times, its interval, whether it is
 * new, then its attributes, each a name and a value of a closed set of types. The same
 * code writes them under a JDK and compiled by rontolisp, which has no Java
 * serialization; a value of another type is refused where it is set.
 */
final class SessionCodec {

	/** The format's version, the first octet: a value of another is no session. */
	static final int VERSION = 1;

	static final String KEPT = "a String, a Boolean, a Character, a Byte, a Short, an Integer, a Long, a Float, "
			+ "a Double, a byte[], and a List, a Set or a Map of them";

	private static final int NULL = 0;

	private static final int STRING = 's';

	private static final int BOOLEAN = 'z';

	private static final int CHARACTER = 'c';

	private static final int BYTE = 'b';

	private static final int SHORT = 'h';

	private static final int INTEGER = 'i';

	private static final int LONG = 'j';

	private static final int FLOAT = 'f';

	private static final int DOUBLE = 'd';

	private static final int OCTETS = 'y';

	private static final int LIST = 'l';

	private static final int SET = 't';

	private static final int MAP = 'm';

	private SessionCodec() {
	}

	/**
	 * The octets of a session.
	 * @throws IllegalArgumentException where an attribute is of no type kept
	 */
	static byte[] encode(SessionData session) {
		ByteArrayOutputStream octets = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(octets);
		try {
			out.writeByte(VERSION);
			out.writeLong(session.getCreationTime());
			out.writeLong(session.getAccessedTime());
			out.writeLong(session.getLastAccessedTime());
			out.writeInt(session.getMaxInactiveInterval());
			out.writeBoolean(session.isFresh());
			List<Map.Entry<String, Object>> attributes = new ArrayList<>(session.getAttributes().entrySet());
			out.writeInt(attributes.size());
			for (Map.Entry<String, Object> attribute : attributes) {
				text(out, attribute.getKey());
				value(out, attribute.getKey(), attribute.getValue());
			}
		}
		catch (IOException ex) {
			// Written to memory.
			throw new UncheckedIOException(ex);
		}
		return octets.toByteArray();
	}

	/**
	 * The session of an id, read back from its octets.
	 * @return the session, or null for octets of another version or cut short
	 */
	static @Nullable SessionData decode(String id, byte[] octets) {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(octets));
		try {
			if (in.readUnsignedByte() != VERSION) {
				return null;
			}
			SessionData session = new SessionData(id, in.readLong(), in.readLong(), in.readLong(), in.readInt(),
					in.readBoolean());
			int count = in.readInt();
			Map<String, Object> attributes = session.getAttributes();
			for (int i = 0; i < count; i++) {
				String name = text(in);
				Object value = value(in);
				if (value != null) {
					attributes.put(name, value);
				}
			}
			return (in.read() < 0) ? session : null;
		}
		catch (IOException ex) {
			return null;
		}
	}

	private static void value(DataOutputStream out, String name, @Nullable Object value) throws IOException {
		switch (value) {
			case null -> out.writeByte(NULL);
			case String text -> {
				out.writeByte(STRING);
				text(out, text);
			}
			case Boolean flag -> {
				out.writeByte(BOOLEAN);
				out.writeBoolean(flag);
			}
			case Character c -> {
				out.writeByte(CHARACTER);
				out.writeChar(c);
			}
			case Byte b -> {
				out.writeByte(BYTE);
				out.writeByte(b);
			}
			case Short n -> {
				out.writeByte(SHORT);
				out.writeShort(n);
			}
			case Integer n -> {
				out.writeByte(INTEGER);
				out.writeInt(n);
			}
			case Long n -> {
				out.writeByte(LONG);
				out.writeLong(n);
			}
			case Float n -> {
				out.writeByte(FLOAT);
				out.writeFloat(n);
			}
			case Double n -> {
				out.writeByte(DOUBLE);
				out.writeDouble(n);
			}
			case byte[] octets -> {
				out.writeByte(OCTETS);
				out.writeInt(octets.length);
				out.write(octets);
			}
			case List<?> list -> {
				out.writeByte(LIST);
				elements(out, name, list);
			}
			case Set<?> set -> {
				out.writeByte(SET);
				elements(out, name, set);
			}
			case Map<?, ?> map -> {
				List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
				out.writeByte(MAP);
				out.writeInt(entries.size());
				for (Map.Entry<?, ?> entry : entries) {
					value(out, name, entry.getKey());
					value(out, name, entry.getValue());
				}
			}
			default -> throw new IllegalArgumentException(
					"the session attribute " + name + " is a " + value.getClass().getName()
							+ ", which a session in a key-value store cannot hold: it holds " + KEPT);
		}
	}

	private static void elements(DataOutputStream out, String name, Collection<?> elements) throws IOException {
		List<?> all = new ArrayList<>(elements);
		out.writeInt(all.size());
		for (Object element : all) {
			value(out, name, element);
		}
	}

	private static @Nullable Object value(DataInputStream in) throws IOException {
		int tag = in.readUnsignedByte();
		return switch (tag) {
			case NULL -> null;
			case STRING -> text(in);
			case BOOLEAN -> in.readBoolean();
			case CHARACTER -> in.readChar();
			case BYTE -> in.readByte();
			case SHORT -> in.readShort();
			case INTEGER -> in.readInt();
			case LONG -> in.readLong();
			case FLOAT -> in.readFloat();
			case DOUBLE -> in.readDouble();
			case OCTETS -> octets(in);
			case LIST -> {
				int count = in.readInt();
				List<Object> list = new ArrayList<>();
				for (int i = 0; i < count; i++) {
					list.add(value(in));
				}
				yield list;
			}
			case SET -> {
				int count = in.readInt();
				Set<Object> set = new LinkedHashSet<>();
				for (int i = 0; i < count; i++) {
					set.add(value(in));
				}
				yield set;
			}
			case MAP -> {
				int count = in.readInt();
				Map<Object, Object> map = new LinkedHashMap<>();
				for (int i = 0; i < count; i++) {
					map.put(value(in), value(in));
				}
				yield map;
			}
			default -> throw new IOException("no value is tagged " + tag);
		};
	}

	private static void text(DataOutputStream out, String text) throws IOException {
		byte[] octets = text.getBytes(StandardCharsets.UTF_8);
		out.writeInt(octets.length);
		out.write(octets);
	}

	private static String text(DataInputStream in) throws IOException {
		return new String(octets(in), StandardCharsets.UTF_8);
	}

	private static byte[] octets(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 0 || length > in.available()) {
			throw new IOException("a length of " + length + " past the end");
		}
		byte[] octets = new byte[length];
		in.readFully(octets);
		return octets;
	}

}
