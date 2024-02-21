package top.xudj.spi.dubbo.adaptive.annoclass;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.SPI;

/**
 * 自适应扩展点类
 * 注解：@Adaptive标记类
 */
@SPI(DubboAdaptiveClassSpiImpl2.NAME)
public interface DubboAdaptiveClassSpi {

    void echo(URL url, String msg);

}
