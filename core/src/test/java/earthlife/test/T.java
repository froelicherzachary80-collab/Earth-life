package earthlife.test;

import java.lang.reflect.Method;
import java.util.*;

/** Tiny zero-dependency test runner: runs every public static void test*() in the given classes. */
public final class T {
    public static void main(String[] args) throws Exception {
        Class<?>[] suites = {GeoTest.class, WorldTest.class, EconomyTest.class, AviationTest.class, V02WorldTest.class, V02LifeTest.class, V03Test.class, V04Test.class, V041Test.class, ZmfCinemaTest.class, ZmfMediaTest.class};
        int pass = 0, fail = 0;
        for (Class<?> c : suites) {
            for (Method m : Arrays.stream(c.getDeclaredMethods()).sorted(Comparator.comparing(Method::getName)).toList()) {
                if (!m.getName().startsWith("test")) continue;
                try {
                    m.invoke(null);
                    pass++;
                    System.out.println("  PASS " + c.getSimpleName() + "." + m.getName());
                } catch (java.lang.reflect.InvocationTargetException e) {
                    fail++;
                    System.out.println("  FAIL " + c.getSimpleName() + "." + m.getName() + " -> " + e.getCause());
                    e.getCause().printStackTrace(System.out);
                }
            }
        }
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    public static void check(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }
    public static void near(double a, double b, double tol, String msg) {
        if (Math.abs(a - b) > tol) throw new AssertionError(msg + ": expected " + b + " ±" + tol + " but was " + a);
    }
    public static void throwsEx(Class<? extends Throwable> t, ThrowingRunnable r, String msg) {
        try { r.run(); } catch (Throwable e) { if (t.isInstance(e)) return; throw new AssertionError(msg + ": wrong exception " + e); }
        throw new AssertionError(msg + ": expected " + t.getSimpleName());
    }
    public interface ThrowingRunnable { void run() throws Exception; }

    public static java.nio.file.Path tmpDir() throws java.io.IOException {
        return java.nio.file.Files.createTempDirectory("earthlife-test");
    }
}
