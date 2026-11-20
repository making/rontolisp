import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.lang.reflect.AccessFlag;

// Measurement only: for every call that looks a resource up by name in the class files of
// the jars given, where the name (or the class of a ServiceLoader) comes from.
public class Scan {

	record Origin(String kind, String detail) {
		public String toString() {
			return detail == null ? kind : kind + "(" + detail + ")";
		}
	}

	static final Origin UNKNOWN = new Origin("UNKNOWN", null);

	static final Set<String> TARGETS = Set.of("java/lang/Class.getResourceAsStream", "java/lang/Class.getResource",
			"java/lang/ClassLoader.getResourceAsStream", "java/lang/ClassLoader.getResource",
			"java/lang/ClassLoader.getResources", "java/lang/ClassLoader.getSystemResourceAsStream",
			"java/lang/ClassLoader.getSystemResource", "java/lang/ClassLoader.getSystemResources",
			"java/util/ResourceBundle.getBundle", "java/util/ServiceLoader.load",
			"java/util/ServiceLoader.loadInstalled", "java/util/Properties.load");

	public static void main(String[] args) throws Exception {
		for (String line : Files.readAllLines(Path.of(args[0]))) {
			Path jar = Path.of(System.getProperty("user.home"), ".m2/repository", line);
			String artifact = line;
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
					ZipEntry entry = e.nextElement();
					if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/versions/")) {
						continue;
					}
					byte[] bytes = zip.getInputStream(entry).readAllBytes();
					try {
						scan(artifact, ClassFile.of().parse(bytes));
					}
					catch (RuntimeException ex) {
						// a class the API cannot read
					}
				}
			}
			catch (Exception ex) {
				System.err.println("skip " + line + ": " + ex);
			}
		}
	}

	static void scan(String artifact, ClassModel model) {
		for (MethodModel method : model.methods()) {
			Optional<CodeModel> code = method.code();
			if (code.isEmpty()) {
				continue;
			}
			boolean isStatic = method.flags().has(AccessFlag.STATIC);
			MethodTypeDesc type = method.methodTypeSymbol();
			int params = (isStatic ? 0 : 1);
			Map<Integer, Integer> paramSlots = new HashMap<>();
			int slot = isStatic ? 0 : 1;
			for (int i = 0; i < type.parameterCount(); i++) {
				paramSlots.put(slot, i);
				ClassDesc p = type.parameterType(i);
				slot += (p.equals(ConstantDescs.CD_long) || p.equals(ConstantDescs.CD_double)) ? 2 : 1;
			}
			walk(artifact, model.thisClass().asInternalName() + "." + method.methodName().stringValue(),
					code.get(), paramSlots);
		}
	}

	static void walk(String artifact, String where, CodeModel code, Map<Integer, Integer> paramSlots) {
		Deque<Origin> stack = new ArrayDeque<>();
		Map<Label, Deque<Origin>> atLabel = new HashMap<>();
		Map<Integer, Origin> locals = new HashMap<>();
		Set<Integer> stored = new HashSet<>();
		boolean dead = false;
		for (CodeElement element : code) {
			if (element instanceof LabelTarget target) {
				Deque<Origin> saved = atLabel.get(target.label());
				if (dead) {
					stack = saved == null ? new ArrayDeque<>() : new ArrayDeque<>(saved);
					dead = false;
				}
				continue;
			}
			if (element instanceof ExceptionCatch) {
				continue;
			}
			if (!(element instanceof Instruction ins)) {
				continue;
			}
			if (dead) {
				stack = new ArrayDeque<>();
				dead = false;
			}
			switch (ins) {
				case ConstantInstruction c -> {
					Object v = c.constantValue();
					if (v instanceof String s) {
						push(stack, new Origin("CONST", '"' + s + '"'));
					}
					else if (v instanceof ClassDesc d) {
						push(stack, new Origin("CONST", d.displayName() + ".class"));
					}
					else {
						push(stack, UNKNOWN, c.typeKind().slotSize());
					}
				}
				case LoadInstruction l -> {
					if (l.typeKind() == TypeKind.REFERENCE) {
						Origin known = locals.get(l.slot());
						if (known != null) {
							push(stack, known);
						}
						else if (paramSlots.containsKey(l.slot()) && !stored.contains(l.slot())) {
							push(stack, new Origin("PARAM", null));
						}
						else {
							push(stack, new Origin("LOCAL", null));
						}
					}
					else {
						push(stack, UNKNOWN, l.typeKind().slotSize());
					}
				}
				case StoreInstruction s -> {
					Origin v = pop(stack, s.typeKind().slotSize());
					stored.add(s.slot());
					if (s.typeKind() == TypeKind.REFERENCE && v != UNKNOWN) {
						locals.put(s.slot(), v);
					}
					else {
						locals.remove(s.slot());
					}
				}
				case FieldInstruction f -> {
					int size = TypeKind.from(f.typeSymbol()).slotSize();
					switch (f.opcode()) {
						case GETSTATIC -> push(stack, new Origin("STATIC", f.name().stringValue()), size);
						case GETFIELD -> {
							pop(stack, 1);
							push(stack, new Origin("FIELD", f.name().stringValue()), size);
						}
						case PUTSTATIC -> pop(stack, size);
						default -> {
							pop(stack, size);
							pop(stack, 1);
						}
					}
				}
				case InvokeInstruction i -> {
					MethodTypeDesc t = i.typeSymbol();
					String key = i.owner().asInternalName() + "." + i.name().stringValue();
					List<Origin> argOrigins = new ArrayList<>();
					for (int k = t.parameterCount() - 1; k >= 0; k--) {
						argOrigins.add(0, pop(stack, TypeKind.from(t.parameterType(k)).slotSize()));
					}
					Origin receiver = null;
					if (i.opcode() != Opcode.INVOKESTATIC) {
						receiver = pop(stack, 1);
					}
					if (TARGETS.contains(key)) {
						Origin name = argOrigins.isEmpty() ? new Origin("NOARG", null) : argOrigins.get(0);
						System.out.println(artifact + "\t" + key + t.descriptorString() + "\t" + name.kind() + "\t"
								+ (name.detail() == null ? "" : name.detail()) + "\t" + where);
					}
					TypeKind ret = TypeKind.from(t.returnType());
					if (ret != TypeKind.VOID) {
						Origin result;
						if (key.equals("java/lang/StringBuilder.toString") || key.equals("java/lang/StringBuffer.toString")) {
							result = new Origin("CONCAT", receiver == null ? null : receiver.detail());
						}
						else if (key.equals("java/lang/StringBuilder.append") && receiver != null) {
							String parts = receiver.detail() == null ? "" : receiver.detail();
							Origin arg = argOrigins.get(0);
							parts += arg.kind().equals("CONST") ? arg.detail() : "<" + arg.kind() + ">";
							result = new Origin("SB", parts);
						}
						else if (key.equals("java/lang/StringBuilder.<init>")) {
							result = UNKNOWN;
						}
						else {
							result = new Origin("CALL", key.substring(key.lastIndexOf('/') + 1));
						}
						push(stack, result, ret.slotSize());
					}
					else if (i.name().stringValue().equals("<init>") && receiver != null
							&& i.owner().asInternalName().equals("java/lang/StringBuilder")) {
						// new SB; dup; [arg]; invokespecial <init>: the dup'd copy stays
						String parts = "";
						if (!argOrigins.isEmpty()) {
							Origin arg = argOrigins.get(0);
							parts = arg.kind().equals("CONST") ? arg.detail() : "<" + arg.kind() + ">";
						}
						if (!stack.isEmpty()) {
							stack.pop();
							stack.push(new Origin("SB", parts));
						}
					}
				}
				case InvokeDynamicInstruction d -> {
					MethodTypeDesc t = d.typeSymbol();
					List<Origin> argOrigins = new ArrayList<>();
					for (int k = t.parameterCount() - 1; k >= 0; k--) {
						argOrigins.add(0, pop(stack, TypeKind.from(t.parameterType(k)).slotSize()));
					}
					TypeKind ret = TypeKind.from(t.returnType());
					if (ret != TypeKind.VOID) {
						if (d.name().stringValue().equals("makeConcatWithConstants")) {
							String recipe = d.bootstrapArgs().isEmpty() ? "" : String.valueOf(d.bootstrapArgs().get(0));
							StringBuilder shown = new StringBuilder();
							int a = 0;
							for (char ch : recipe.toCharArray()) {
								if (ch == '\u0001') {
									Origin arg = a < argOrigins.size() ? argOrigins.get(a++) : UNKNOWN;
									shown.append(arg.kind().equals("CONST") ? arg.detail() : "<" + arg.kind()
											+ (arg.detail() == null ? "" : ":" + arg.detail()) + ">");
								}
								else {
									shown.append(ch);
								}
							}
							push(stack, new Origin("CONCAT", shown.toString()), 1);
						}
						else {
							push(stack, new Origin("INDY", d.name().stringValue()), ret.slotSize());
						}
					}
				}
				case NewObjectInstruction n -> push(stack, new Origin("NEW", n.className().asInternalName()));
				case StackInstruction s -> stackOp(stack, s.opcode());
				case TypeCheckInstruction t -> {
					if (t.opcode() == Opcode.INSTANCEOF) {
						pop(stack, 1);
						push(stack, UNKNOWN);
					}
				}
				case BranchInstruction b -> {
					switch (b.opcode()) {
						case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE, IFNULL, IFNONNULL -> pop(stack, 1);
						case GOTO, GOTO_W -> {
						}
						default -> pop(stack, 2);
					}
					atLabel.putIfAbsent(b.target(), new ArrayDeque<>(stack));
					if (b.opcode() == Opcode.GOTO || b.opcode() == Opcode.GOTO_W) {
						dead = true;
					}
				}
				case ReturnInstruction r -> dead = true;
				case ThrowInstruction t -> dead = true;
				case LookupSwitchInstruction l -> {
					pop(stack, 1);
					dead = true;
					for (SwitchCase c : l.cases()) {
						atLabel.putIfAbsent(c.target(), new ArrayDeque<>(stack));
					}
					atLabel.putIfAbsent(l.defaultTarget(), new ArrayDeque<>(stack));
				}
				case TableSwitchInstruction l -> {
					pop(stack, 1);
					dead = true;
					for (SwitchCase c : l.cases()) {
						atLabel.putIfAbsent(c.target(), new ArrayDeque<>(stack));
					}
					atLabel.putIfAbsent(l.defaultTarget(), new ArrayDeque<>(stack));
				}
				default -> generic(stack, ins);
			}
		}
	}

	// the stack effect of the instructions not followed above, by opcode
	static void generic(Deque<Origin> stack, Instruction ins) {
		Opcode op = ins.opcode();
		switch (ins) {
			case OperatorInstruction o -> {
				TypeKind k = o.typeKind();
				switch (op) {
					case INEG, LNEG, FNEG, DNEG -> {
					}
					case ARRAYLENGTH -> {
						pop(stack, 1);
						push(stack, UNKNOWN);
					}
					case LCMP, FCMPL, FCMPG, DCMPL, DCMPG -> {
						pop(stack, k.slotSize());
						pop(stack, k.slotSize());
						push(stack, UNKNOWN);
					}
					case ISHL, ISHR, IUSHR, LSHL, LSHR, LUSHR -> {
						pop(stack, 1);
					}
					default -> {
						pop(stack, k.slotSize());
					}
				}
			}
			case ConvertInstruction c -> {
				pop(stack, c.fromType().slotSize());
				push(stack, UNKNOWN, c.toType().slotSize());
			}
			case ArrayLoadInstruction a -> {
				pop(stack, 1);
				pop(stack, 1);
				push(stack, a.typeKind() == TypeKind.REFERENCE ? new Origin("ARRAY", null) : UNKNOWN,
						a.typeKind().slotSize());
			}
			case ArrayStoreInstruction a -> {
				pop(stack, a.typeKind().slotSize());
				pop(stack, 1);
				pop(stack, 1);
			}
			case NewReferenceArrayInstruction n -> {
				pop(stack, 1);
				push(stack, UNKNOWN);
			}
			case NewPrimitiveArrayInstruction n -> {
				pop(stack, 1);
				push(stack, UNKNOWN);
			}
			case NewMultiArrayInstruction n -> {
				for (int i = 0; i < n.dimensions(); i++) {
					pop(stack, 1);
				}
				push(stack, UNKNOWN);
			}
			case MonitorInstruction m -> pop(stack, 1);
			case IncrementInstruction i -> {
			}
			case NopInstruction n -> {
			}
			default -> {
			}
		}
	}

	static void stackOp(Deque<Origin> stack, Opcode op) {
		switch (op) {
			case POP -> pop1(stack);
			case POP2 -> {
				pop1(stack);
				pop1(stack);
			}
			case DUP -> {
				Origin a = pop1(stack);
				stack.push(a);
				stack.push(a);
			}
			case DUP_X1 -> {
				Origin a = pop1(stack), b = pop1(stack);
				stack.push(a);
				stack.push(b);
				stack.push(a);
			}
			case DUP_X2 -> {
				Origin a = pop1(stack), b = pop1(stack), c = pop1(stack);
				stack.push(a);
				stack.push(c);
				stack.push(b);
				stack.push(a);
			}
			case DUP2 -> {
				Origin a = pop1(stack), b = pop1(stack);
				stack.push(b);
				stack.push(a);
				stack.push(b);
				stack.push(a);
			}
			case DUP2_X1 -> {
				Origin a = pop1(stack), b = pop1(stack), c = pop1(stack);
				stack.push(b);
				stack.push(a);
				stack.push(c);
				stack.push(b);
				stack.push(a);
			}
			case DUP2_X2 -> {
				Origin a = pop1(stack), b = pop1(stack), c = pop1(stack), d = pop1(stack);
				stack.push(b);
				stack.push(a);
				stack.push(d);
				stack.push(c);
				stack.push(b);
				stack.push(a);
			}
			case SWAP -> {
				Origin a = pop1(stack), b = pop1(stack);
				stack.push(a);
				stack.push(b);
			}
			default -> {
			}
		}
	}

	static Origin pop1(Deque<Origin> stack) {
		return stack.isEmpty() ? UNKNOWN : stack.pop();
	}

	static Origin pop(Deque<Origin> stack, int size) {
		Origin top = pop1(stack);
		if (size == 2) {
			pop1(stack);
		}
		return top;
	}

	static void push(Deque<Origin> stack, Origin o) {
		stack.push(o);
	}

	static void push(Deque<Origin> stack, Origin o, int size) {
		if (size == 2) {
			stack.push(UNKNOWN);
		}
		stack.push(o);
	}
}
