package top.xudj.spi.dubbo.inject;

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.model.ApplicationModel;

/**
 * dubbo spi inject 演示
 */
public class DubboSpiInjectDemo {

    public static void main(String[] args) {
        ExtensionLoader<DubboSpiInject> extensionLoader = ApplicationModel.defaultModel().getExtensionLoader(DubboSpiInject.class);
        DubboSpiInject dubboSpiInject = extensionLoader.getExtension(DubboSpiInjectImpl.NAME);
        dubboSpiInject.echo("hello");
    }

}
