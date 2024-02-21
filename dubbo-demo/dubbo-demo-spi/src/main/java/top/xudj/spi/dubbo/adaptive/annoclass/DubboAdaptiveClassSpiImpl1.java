package top.xudj.spi.dubbo.adaptive.annoclass;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;

/**
 * 自适应扩展点类
 * 注解：@Adaptive标记类
 */
@Adaptive
public class DubboAdaptiveClassSpiImpl1 implements DubboAdaptiveClassSpi {

    public static final String NAME = "impl1";

    @Override
    public void echo(URL url, String msg) {
        System.out.println("DubboAdaptiveClassSpiImpl1 echo url: " + url + ", msg: " + msg);
    }

}
