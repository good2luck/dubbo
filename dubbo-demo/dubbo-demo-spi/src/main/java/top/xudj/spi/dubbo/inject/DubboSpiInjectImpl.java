package top.xudj.spi.dubbo.inject;

import top.xudj.spi.dubbo.adaptive.DubboAdaptiveSpi;

/**
 * dubbo spi inject
 */
public class DubboSpiInjectImpl implements DubboSpiInject {

    public static final String NAME = "dubboSpiInject";

    @Override
    public void echo(String msg) {
        System.out.println("DubboSpiInjectImpl echo:" + msg);
    }

    /**
     * 自动注入
     *
     * 先查询ScopeBeanFactory，再查询ExtensionLoader
     *  查询ExtensionLoader时，查找自适应扩展实例
     *
     * @param dubboAdaptiveSpi
     */
    @Override
    public void setDubboAdaptiveSpi(DubboAdaptiveSpi dubboAdaptiveSpi) {
        System.out.println("DubboSpiInjectImpl setDubboAdaptiveSpi:" + dubboAdaptiveSpi);
    }

}
