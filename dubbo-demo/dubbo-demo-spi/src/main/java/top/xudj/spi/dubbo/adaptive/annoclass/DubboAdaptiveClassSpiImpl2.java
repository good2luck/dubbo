package top.xudj.spi.dubbo.adaptive.annoclass;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;

/**
 * 自适应扩展点类
 */
@Adaptive
public class DubboAdaptiveClassSpiImpl2 implements DubboAdaptiveClassSpi {

    public static final String NAME = "impl2";

    @Override
    public void echo(URL url, String msg) {
        System.out.println("DubboAdaptiveClassSpiImpl2 echo url: " + url + ", msg: " + msg);
    }

}
