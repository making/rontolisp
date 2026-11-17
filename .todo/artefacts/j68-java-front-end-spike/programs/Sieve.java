abstract class Node { abstract int eval(); }
class Num extends Node { int v; Num(int v) { this.v = v; } int eval() { return v; } }
class Add extends Node { Node l, r; Add(Node l, Node r) { this.l = l; this.r = r; } int eval() { return l.eval() + r.eval(); } }
class Mul extends Node { Node l, r; Mul(Node l, Node r) { this.l = l; this.r = r; } int eval() { return l.eval() * r.eval(); } }
public class Sieve {
    static Node build(int depth) {
        if (depth == 0) return new Num(depth + 3);
        return depth % 2 == 0 ? new Add(build(depth - 1), build(depth - 1)) : new Mul(build(depth - 1), build(depth - 1));
    }
    public static void main(String[] args) {
        int n = 5000000;
        int[] composite = new int[n + 1];
        int count = 0;
        for (int i = 2; i <= n; i++) {
            if (composite[i] == 0) {
                count++;
                for (int j = i + i; j <= n; j += i) composite[j] = 1;
            }
        }
        System.out.println(count);
        Node tree = build(18);
        int sum = 0;
        for (int i = 0; i < 20; i++) sum += tree.eval();
        System.out.println(sum);
    }
}
