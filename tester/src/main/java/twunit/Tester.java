package twunit;

public class Tester {
    private static int A;

    private Tester() {
    }

    private static void w() {
        System.out.println("Tester.w() called");
    }

    public static void main(String[] args) {
        System.out.println("Hello World!");
        A = 10;
        Mock.run();
    }

    public static class Mock {
        public static void run() {
            System.out.println("A = " + A);
            w();
            //noinspection InstantiationOfUtilityClass
            new Tester();
            new Interface0() {
            }.invoke();
        }
    }

    public interface Interface0 {
        private void run() {
            System.out.println("Hi!");
        }

        default void invoke() {
            run();
        }
    }
}
