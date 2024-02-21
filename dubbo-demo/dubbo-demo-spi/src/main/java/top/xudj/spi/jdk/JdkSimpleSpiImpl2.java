package top.xudj.spi.jdk;

/**
 * JdkSimpleSpiImpl2
 */
public class JdkSimpleSpiImpl2 implements JdkSimpleSpi {

    /**
     * 构造函数
     */
    public JdkSimpleSpiImpl2() {
        // System.out.println("JdkSimpleSpiImpl2 constructor");
    }

    @Override
    public void sayHello() {
        System.out.println("JdkSimpleSpiImpl2 say hello");
    }
}
