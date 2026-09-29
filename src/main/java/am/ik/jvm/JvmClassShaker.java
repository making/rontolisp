package am.ik.jvm;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Language-independent dead-code eliminator (tree shaker) for a JVM class file, the JVM
 * counterpart of {@link am.ik.wasm.WasmTreeShaker}.
 * <p>
 * The rontolisp JVM code generator emits every runtime helper method (print / numeric /
 * reader / eval helpers, built-in function wrappers, ...) unconditionally, so a compiled
 * class embeds the whole runtime even when the program uses almost none of it. This pass
 * removes that bloat: it builds a call graph from the {@code invoke*} instructions of
 * every method body, computes the set of methods reachable from the class's roots (given
 * by name, e.g. {@code main}), drops the rest along with any field no surviving method
 * references, and writes the class with a fresh constant pool holding only what the
 * survivors use. {@code java.lang.classfile} reads and writes the class: an {@code ldc}
 * whose constant moved past index 255 becomes an {@code ldc_w} and the branches around it
 * follow, so no instruction layout is assumed.
 * <p>
 * The pass is purely additive: it is a separate call over a finished class file, so
 * emission itself never runs it and a caller that does not ask keeps the deterministic
 * output byte for byte (rontolisp asks at every {@code --optimize} level but
 * {@code off}).
 * <p>
 * A {@code StackMapTable} is dropped, not preserved: run {@link StackMapFrames} after
 * shaking to restore it. Every other attribute (a {@code LineNumberTable} included) is
 * kept. Dynamically-reached methods stay alive the same way they do on WASM: first-class
 * calls go through dispatch methods whose bodies contain real {@code invokestatic}s to
 * every registered function. The one edge invisible to bytecode is a reflective call by
 * name; the caller lists such methods as extra roots (rontolisp: {@code _apply}, looked
 * up reflectively by the embedded {@code java:} bridge).
 * <p>
 * The same call graph answers the opposite question, and
 * {@link #unresolvedSelfMethods(byte[])} exposes it: which own-class methods does the
 * emitted bytecode call that the class never declares? That one runs on every build,
 * including one that declined the shake with {@code --optimize=off}.
 */
public final class JvmClassShaker {

	private static final ClassFile SHAKE = ClassFile.of(ClassFile.ConstantPoolSharingOption.NEW_POOL,
			ClassFile.StackMapsOption.DROP_STACK_MAPS);

	private JvmClassShaker() {
	}

	/**
	 * Removes methods unreachable from the given roots, drops fields no surviving method
	 * references, and compacts the constant pool.
	 * @param classFile a JVM class file
	 * @param rootMethodNames names of the entry-point methods to keep (with everything
	 * they transitively reach); {@code <init>}/{@code <clinit>} are always kept
	 * @return an equivalent class file with dead methods removed and no
	 * {@code StackMapTable}; the input itself when that is what the pass wrote
	 */
	public static byte[] shake(byte[] classFile, Set<String> rootMethodNames) {
		ClassModel model = SHAKE.parse(classFile);
		OwnCallGraph graph = graph(model);
		boolean[] kept = graph.reachable(rootMethodNames);
		Set<OwnCallGraph.Member> keptMethods = new HashSet<>();
		List<MethodModel> methods = model.methods();
		for (int m = 0; m < methods.size(); m++) {
			if (kept[m]) {
				keptMethods.add(member(methods.get(m)));
			}
		}
		Set<OwnCallGraph.Member> usedFields = graph.usedFields(kept);
		byte[] result = SHAKE.transformClass(model,
				ClassTransform
					.dropping(element -> element instanceof MethodModel method && !keptMethods.contains(member(method))
							|| element instanceof FieldModel field
									&& !usedFields.contains(new OwnCallGraph.Member(field.fieldName().stringValue(),
											field.fieldType().stringValue()))));
		return Arrays.equals(result, classFile) ? classFile : result;
	}

	/**
	 * Returns every own-class method that some emitted body actually {@code invoke}s but
	 * the class does not declare, in first-reference order (empty when the class is
	 * self-consistent).
	 * <p>
	 * JVM method resolution is lazy, so such a reference survives verification and class
	 * loading and throws {@link NoSuchMethodError} only if the branch containing it is
	 * ever taken. That makes it the exact failure mode of a code generator whose
	 * runtime-helper emission is decided by a PREDICTION (a source scan) rather than by
	 * what the bodies turned out to reference: the mismatch is invisible until a user's
	 * program takes the branch. Scanning the finished class turns it back into a
	 * compile-time fact -- see {@code .kb/adjustable-arrays.md} for the rontolisp gate
	 * this exists for. The scan reads only what the bytecode references, so a
	 * constant-pool entry minted speculatively and never emitted is not reported.
	 * @param classFile a JVM class file
	 * @return the unresolved own-class calls, in first-reference order
	 */
	public static List<UnresolvedSelfMethod> unresolvedSelfMethods(byte[] classFile) {
		return unresolved(graph(ClassFile.of().parse(classFile)));
	}

	/**
	 * The graph's unresolved own calls, as {@link #unresolvedSelfMethods} reports them.
	 * @param graph the class's own-call graph
	 * @return the unresolved calls, in first-reference order
	 */
	static List<UnresolvedSelfMethod> unresolved(OwnCallGraph graph) {
		List<UnresolvedSelfMethod> result = new ArrayList<>();
		graph.unresolved()
			.forEach((call, callers) -> result
				.add(new UnresolvedSelfMethod(call.name(), call.descriptor(), List.copyOf(callers))));
		return List.copyOf(result);
	}

	/**
	 * An own-class method some emitted body invokes but the class never declares.
	 *
	 * @param name the called method's name
	 * @param descriptor its descriptor
	 * @param callers the names of the declared methods whose bodies reference it, in
	 * declaration order
	 */
	public record UnresolvedSelfMethod(String name, String descriptor, List<String> callers) {

		@Override
		public String toString() {
			return this.name + this.descriptor + " (called from " + String.join(", ", this.callers) + ")";
		}
	}

	private static OwnCallGraph.Member member(MethodModel method) {
		return new OwnCallGraph.Member(method.methodName().stringValue(), method.methodType().stringValue());
	}

	private static OwnCallGraph graph(ClassModel model) {
		String self = model.thisClass().asInternalName();
		OwnCallGraph graph = new OwnCallGraph();
		for (MethodModel method : model.methods()) {
			List<OwnCallGraph.Member> calls = new ArrayList<>();
			List<OwnCallGraph.Member> fields = new ArrayList<>();
			CodeModel code = method.code().orElse(null);
			if (code != null) {
				for (CodeElement element : code) {
					if (element instanceof InvokeInstruction invoke) {
						if (self.equals(invoke.owner().asInternalName())) {
							calls
								.add(new OwnCallGraph.Member(invoke.name().stringValue(), invoke.type().stringValue()));
						}
					}
					else if (element instanceof FieldInstruction access
							&& self.equals(access.owner().asInternalName())) {
						fields.add(new OwnCallGraph.Member(access.name().stringValue(), access.type().stringValue()));
					}
				}
			}
			graph.method(member(method), calls, fields);
		}
		return graph;
	}

}
