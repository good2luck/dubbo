package top.xudj.spi.dubbo.adaptive;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.url.component.ServiceConfigURL;
import org.apache.dubbo.rpc.model.ApplicationModel;

import java.util.HashMap;
import java.util.Map;

/**
 * 自适应测试
 * 注解：@Adaptive
 *
 * 存在自适应类的两种情况：
 * 1）、存在@Adaptive注解的类，优先使用
 * 2）、接口存在@Adaptive注解的方法
 */
public class DubboAdaptiveSpiDemo {

    public static void main(String[] args) {
        ExtensionLoader<DubboAdaptiveSpi> extensionLoader = ApplicationModel.defaultModel()
                .getExtensionLoader(DubboAdaptiveSpi.class);
        DubboAdaptiveSpi adaptiveExtension = extensionLoader.getAdaptiveExtension();

        // 构造参数
        Map<String, String> map = new HashMap<>();
        // 1、@SPI(DubboAdaptiveSpiImpl1.NAME)，@Adaptive
        //  map.put("dubbo.adaptive.spi", "impl2"); // 使用DubboAdaptiveSpiImpl2
        //  map.put("impl1", "impl2"); // 使用默认的扩展点实现DubboAdaptiveSpiImpl1
        //  map.put("", ""); // 使用默认的扩展点实现DubboAdaptiveSpiImpl1
        // 2、在1的基础上，去掉注解@SPI(DubboAdaptiveSpiImpl1.NAME)后，抛出异常 java.lang.IllegalStateException: Failed to get extension，use keys([dubbo.adaptive.spi])
        // 3、在2的基础上，添加@Adaptive("adaptive")，抛出异常 java.lang.IllegalStateException: Failed to get extension，use keys([adaptive])
        map.put("adaptive", "impl2"); // 使用DubboAdaptiveSpiImpl2，url:dubbo://1.2.3.4:1010/path1?adaptive=impl2
        URL url = new ServiceConfigURL("dubbo", "1.2.3.4", 1010, "path1", map);
        adaptiveExtension.echo(url, "hello");
        // 小结：扩展点name = url.getParameter(${key}, ${default})
        // 1）、url查找参数${key}的顺序，首先是@Adaptive的value值(多个依次匹配)，如果没配置则使用接口名转化(DubboAdaptiveSpi -> dubbo.adaptive.spi作为key)匹配，匹配到value值作为对应的扩展点实现
        // 2）、如果匹配不到则使用默认的扩展点实现${default}（即@SPI的值），如果没有默认的扩展点实现（@SPI未配置value）则抛出异常
    }

}
