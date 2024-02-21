package top.xudj.spi.dubbo.activate;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.extension.SPI;

/**
 * @Activate 自动激活扩展
 */
@Activate(group = "group1", value = {"key1:impl1", "key"})
public class DubboActivateSpiImpl1 implements DubboActivateSpi {

    public static final String NAME = "impl1";

    @Override
    public void echo(URL url, String msg) {
        System.out.println("DubboActivateSpiImpl1 echo url: " + url + ", msg: " + msg);
    }

}
