package demo.api;

public interface Greeter {
    String greet(String who);

    static Greeter load() {
        for (Greeter g : java.util.ServiceLoader.load(Greeter.class, Thread.currentThread().getContextClassLoader())) return g;
        throw new IllegalStateException("no Greeter implementation");
    }
}
