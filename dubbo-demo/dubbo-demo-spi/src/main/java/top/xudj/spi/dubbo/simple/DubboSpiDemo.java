package top.xudj.spi.dubbo.simple;

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.model.ApplicationModel;

/**
 * DubboSpiDemo
 */
public class DubboSpiDemo {

    /**
     * Dubbo spi 调用流程
     * 1. 通过getExtensionLoader方法获取DubboSimpleSpi接口的ExtensionLoader
     * 2. 通过getExtension方法获取DubboSimpleSpi接口的实现类
     * 3. 调用实现类的sayHello方法
     */
    public static void main(String[] args) {
        // 方法已经废弃了
        // ExtensionLoader.getExtensionLoader(DubboSimpleSpi.class);
        // ScopeModel applicationModel = ScopeModelUtil.getOrDefault(null, DubboSimpleSpi.class);
        ApplicationModel applicationModel = ApplicationModel.defaultModel();
        ExtensionLoader<DubboSimpleSpi> extensionLoader = applicationModel.getExtensionLoader(DubboSimpleSpi.class);
        DubboSimpleSpi dubboSimpleSpi = extensionLoader.getExtension(DubboSimpleSpiImpl2.NAME);
        dubboSimpleSpi.sayHello();

        DubboSimpleSpi dubboSimpleSpiDefault = extensionLoader.getExtension(Boolean.TRUE.toString());
        dubboSimpleSpiDefault.sayHello();

    }

}
