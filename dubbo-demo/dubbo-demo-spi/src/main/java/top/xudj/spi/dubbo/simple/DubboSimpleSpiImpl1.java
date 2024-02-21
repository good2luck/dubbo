package top.xudj.spi.dubbo.simple;

/**
 * DubboSimpleSpiImpl1
 */
public class DubboSimpleSpiImpl1 implements DubboSimpleSpi {

    public static final String NAME = "dubboSimpleSpiImpl1";

    @Override
    public void sayHello() {
        System.out.println("DubboSimpleSpiImpl1:" + this + " say hello");
    }
}
