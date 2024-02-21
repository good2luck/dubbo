package top.xudj.spi.dubbo.wrapper;

import org.apache.dubbo.rpc.model.ApplicationModel;

import top.xudj.spi.dubbo.simple.DubboSimpleSpi;
import top.xudj.spi.dubbo.simple.DubboSimpleSpiImpl2;

/**
 * 扩展点包装类
 */
public class DubboSpiWrapperDemo {

    public static void main(String[] args) {
        // 获取DubboSimpleSpiImpl2.NAME对应的实现类
        DubboSimpleSpi dubboSimpleSpi = ApplicationModel.defaultModel().getExtensionLoader(DubboSimpleSpi.class)
                .getExtension(DubboSimpleSpiImpl2.NAME);
        dubboSimpleSpi.sayHello();
    }

}
