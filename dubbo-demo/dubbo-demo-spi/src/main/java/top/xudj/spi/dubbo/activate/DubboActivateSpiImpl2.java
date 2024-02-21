package top.xudj.spi.dubbo.activate;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;

/**
 * @Activate 自动激活扩展
 */
@Activate(group = "group1", value = {"key2:impl2", "key"})
public class DubboActivateSpiImpl2 implements DubboActivateSpi {

    public static final String NAME = "impl2";

    @Override
    public void echo(URL url, String msg) {
        System.out.println("DubboActivateSpiImpl2 echo url: " + url + ", msg: " + msg);
    }

}
