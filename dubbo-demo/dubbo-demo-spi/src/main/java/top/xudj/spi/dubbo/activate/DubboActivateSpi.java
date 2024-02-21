package top.xudj.spi.dubbo.activate;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.SPI;

/**
 * @Activate 自动激活扩展
 */
@SPI
public interface DubboActivateSpi {

    void echo(URL url, String msg);

}
