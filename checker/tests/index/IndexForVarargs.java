import org.checkerframework.checker.index.qual.IndexFor;

public class IndexForVarargs {
    String get(@IndexFor("#2") int i, String... varargs) {
        return varargs[i];
    }

    void m() {
        get(1, "a", "b");
        // :: error: (argument.type.incompatible)
        get(2, "abc");
    }
}
