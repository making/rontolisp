int square(int x) { return x * x; }

void main() {
    var total = 0;
    for (var i = 1; i <= 10; i++) total += square(i);
    IO.println("sum of squares: " + total);
}
