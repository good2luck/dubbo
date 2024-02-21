package top.xudj.spi.dubbo.adaptive;

import org.apache.dubbo.common.URL;

/**
 * 扩展点实现
 *
 */
public class DubboAdaptiveSpiImpl2 implements DubboAdaptiveSpi {

    public static final String NAME = "impl2";

    @Override
    public String echo(URL url, String s) {

        System.out.println("DubboAdaptiveSpiImpl2 echo url:" + url.toFullString() + ", s:" + s);

        return this.getClass().getSimpleName();
    }

    @Override
    public void play(String s) {
        System.out.println("DubboAdaptiveSpiImpl2 play s:" + s);
    }
}
