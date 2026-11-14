package demo.app;

public class Main {
    public static void main(String[] args) {
        System.out.println(demo.api.Greeter.load().greet("bundle"));
        boolean visible;
        try {
            Class.forName("org.apache.commons.text.WordUtils", false, Main.class.getClassLoader());
            visible = true;
        } catch (ClassNotFoundException e) {
            visible = false;
        }
        System.out.println("implementation dependencies visible to the app: " + visible);
    }
}
