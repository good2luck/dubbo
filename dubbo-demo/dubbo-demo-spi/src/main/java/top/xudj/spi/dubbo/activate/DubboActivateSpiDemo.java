package top.xudj.spi.dubbo.activate;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.url.component.ServiceConfigURL;
import org.apache.dubbo.rpc.model.ApplicationModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Activate 自动激活扩展 测试
 */
public class DubboActivateSpiDemo {

    public static void main(String[] args) {
        ExtensionLoader<DubboActivateSpi> extensionLoader = ApplicationModel.defaultModel().getExtensionLoader(DubboActivateSpi.class);
        // 获取全部的激活扩展
        List<DubboActivateSpi> activateExtensions = new ArrayList<>();
        // activateExtensions = extensionLoader.getActivateExtensions();
        // activateExtensions.forEach(activateExtension -> activateExtension.echo(null, "hello"));

        // 获取特定的激活扩展，根据group和key1
        Map<String, String> map = new HashMap<>();
        map.put("key1", "impl1");     // DubboActivateSpiImpl1作为非默认的激活扩展
        // map.put("key1", "impl2");       // DubboActivateSpiImpl2作为非默认的激活扩展
        // map.put("key1", "impl1,impl2");   // DubboActivateSpiImpl1和DubboActivateSpiImpl2作为非默认的激活扩展
        // map.put("key1", "xxx");         // 空，无非默认，无法匹配默认
        URL url = new ServiceConfigURL("dubbo", "1.2.3.4", 1010, "path1", map);
        activateExtensions = extensionLoader.getActivateExtension(url, "key1", "group1");

        // map.put("key", "xxx");   // DubboActivateSpiImpl1和DubboActivateSpiImpl2作为默认的激活扩展
        // map.put("key", "xxx,-default");   // 空，无非默认
        // map.put("key", "");     // 空，没有激活扩展，无法匹配默认，也无法匹配非默认
        // activateExtensions = extensionLoader.getActivateExtension(url, "key", "group1");
        activateExtensions.forEach(activateExtension -> activateExtension.echo(url, "hello"));
    }

}
