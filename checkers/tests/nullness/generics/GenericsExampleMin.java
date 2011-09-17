import checkers.nullness.quals.*;

// This is the example from manual section:
// "Generics (parametric polymorphism or type polymorphism)"
// whose source code is ../../manual/advanced-features.tex
class GenericsExampleMin {

  class MyList1<@Nullable T> {
    T t;
    @Nullable T nble;
    @NonNull T nn;

    public MyList1(T t, @Nullable T nble, @NonNull T nn) {
        this.t = t;
        this.nble = nble;
        this.nn = nn;
    }

    T get(int i) { return t; }

    // This method works.
    // Note that it fails to work if it is moved after m2() in the syntax tree.
    void m1() {
        t = this.get(0);
        nble = this.get(0);
    }

    // When the assignment to nn is added, the assignments to t and nble also fail, which is unexpected.
    void m2() {
        //:: error: (assignment.type.incompatible)
        nn = null;
        t = this.get(0);
        nble = this.get(0);
    }

  }
}
