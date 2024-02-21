package top.xudj.spi.jdk;

/**
 * JdkSimpleSpiImpl1
 */
public class JdkSimpleSpiImpl1 implements JdkSimpleSpi {

    /**
     * 构造函数
     */
    public JdkSimpleSpiImpl1() {
        // System.out.println("JdkSimpleSpiImpl1 constructor");
    }

    @Override
    public void sayHello() {
        System.out.println("JdkSimpleSpiImpl1 say hello");
    }
}
