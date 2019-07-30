import java.util.HashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.KeyFor;

public abstract class Issue2587 {
    public enum EnumType {
        // :: error: (expression.unparsable.type.invalid) :: error: (assignment.type.incompatible)
        @KeyFor("myMap") MY_KEY,
        // :: error: (assignment.type.incompatible)
        @KeyFor("enumMap") ENUM_KEY;
        private static final Map<String, Integer> enumMap = new HashMap<>();

        void method() {
            @KeyFor("enumMap") EnumType t = ENUM_KEY;
            int x = enumMap.get(ENUM_KEY);
        }
    }

    public static class Inner {
        // :: error: (expression.unparsable.type.invalid) :: error: (assignment.type.incompatible)
        @KeyFor("myMap") String MY_KEY = "";

        public static class Inner2 {
            // :: error: (expression.unparsable.type.invalid) :: error:
            // (assignment.type.incompatible)
            @KeyFor("myMap") String MY_KEY2 = "";
            // :: error: (assignment.type.incompatible)
            @KeyFor("innerMap") String MY_KEY3 = "";
        }

        private static final Map<String, Integer> innerMap = new HashMap<>();
    }

    private final Map<String, Integer> myMap = new HashMap<>();
}
