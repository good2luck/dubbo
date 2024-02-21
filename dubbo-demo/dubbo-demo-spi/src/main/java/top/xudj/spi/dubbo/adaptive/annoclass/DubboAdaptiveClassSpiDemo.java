package top.xudj.spi.dubbo.adaptive.annoclass;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.url.component.ServiceConfigURL;
import org.apache.dubbo.rpc.model.ApplicationModel;

import java.util.HashMap;
import java.util.Map;

/**
 * 自适应扩展点类
 * 注解：@Adaptive标记类
 */
public class DubboAdaptiveClassSpiDemo {

    public static void main(String[] args) {
        // @Adaptive标记类，类为自适应扩展点实现类，通过getAdaptiveExtension()获取
        // 默认只能有一个@Adaptive标记类，多个的话默认会进行覆盖，后者覆盖前者
        ExtensionLoader<DubboAdaptiveClassSpi> extensionLoader = ApplicationModel.defaultModel()
                .getExtensionLoader(DubboAdaptiveClassSpi.class);
        // 1、扩展点实现类，因为DubboAdaptiveClassSpiImpl2注解@Adaptive，抛异常：java.lang.IllegalStateException: No such extension
        // DubboAdaptiveClassSpi extension = extensionLoader.getExtension(DubboAdaptiveClassSpiImpl2.NAME);
        // extension.echo(null, "hello");

        // 2、自适应扩展点实现类
        DubboAdaptiveClassSpi adaptiveExtension = extensionLoader.getAdaptiveExtension();
        // 构造参数
        Map<String, String> map = new HashMap<>();
        URL url = new ServiceConfigURL("dubbo", "1.2.3.4", 1010, "path1", map);
        adaptiveExtension.echo(url, "hello");
    }

}
