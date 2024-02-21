package top.xudj.spi.dubbo.wrapper;

import org.apache.dubbo.common.extension.Wrapper;

import top.xudj.spi.dubbo.simple.DubboSimpleSpi;

/**
 * 扩展点包装类
 *
 * 通过@Wrapper注解可以配置一些条件，是否需要包装，默认会包装
 */
@Wrapper
public class DubboSimpleSpiWrapper implements DubboSimpleSpi {

    // 依赖注入
    private DubboSimpleSpi dubboSimpleSpi;

    public DubboSimpleSpiWrapper(DubboSimpleSpi dubboSimpleSpi) {
        this.dubboSimpleSpi = dubboSimpleSpi;
    }

    @Override
    public void sayHello() {
        System.out.println("DubboSimpleSpiWrapper sayHello");
        dubboSimpleSpi.sayHello();
    }
}
