package top.xudj.spi.dubbo.adaptive;

import org.apache.dubbo.common.URL;

/**
 * 扩展点实现
 */
public class DubboAdaptiveSpiImpl1 implements DubboAdaptiveSpi {

    public static final String NAME = "impl1";

    @Override
    public String echo(URL url, String s) {

        System.out.println("DubboAdaptiveSpiImpl1 echo url:" + url.toFullString() + ", s:" + s);

        return this.getClass().getSimpleName();
    }

    @Override
    public void play(String s) {
        System.out.println("DubboAdaptiveSpiImpl1 play s:" + s);
    }
}
