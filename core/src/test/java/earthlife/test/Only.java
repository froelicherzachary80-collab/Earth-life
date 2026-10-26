package earthlife.test;

import java.lang.reflect.Method;
import java.util.*;

/** Runs the test methods of a single suite: java earthlife.test.Only ZmfCinemaTest */
public final class Only {
    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("earthlife.test." + args[0]);
        int pass = 0, fail = 0;
        for (Method m : Arrays.stream(c.getDeclaredMethods()).sorted(Comparator.comparing(Method::getName)).toList()) {
            if (!m.getName().startsWith("test")) continue;
            long t0 = System.nanoTime();
            try { m.invoke(null); pass++; System.out.printf("  PASS %s (%d ms)%n", m.getName(), (System.nanoTime() - t0) / 1_000_000); }
            catch (java.lang.reflect.InvocationTargetException e) { fail++; System.out.println("  FAIL " + m.getName() + " -> " + e.getCause()); }
        }
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
