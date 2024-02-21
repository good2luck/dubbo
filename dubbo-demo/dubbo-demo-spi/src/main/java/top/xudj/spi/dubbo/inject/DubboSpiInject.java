package top.xudj.spi.dubbo.inject;

import org.apache.dubbo.common.extension.SPI;

import top.xudj.spi.dubbo.adaptive.DubboAdaptiveSpi;

@SPI
public interface DubboSpiInject {

    void echo(String msg);

    void setDubboAdaptiveSpi(DubboAdaptiveSpi dubboAdaptiveSpi);

}
