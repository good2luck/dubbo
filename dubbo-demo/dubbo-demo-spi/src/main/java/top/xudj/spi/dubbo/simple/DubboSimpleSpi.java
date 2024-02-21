package top.xudj.spi.dubbo.simple;

import org.apache.dubbo.common.extension.ExtensionScope;
import org.apache.dubbo.common.extension.SPI;

/**
 * DubboSimpleSpi
 */
@SPI(value = DubboSimpleSpiImpl1.NAME, scope = ExtensionScope.APPLICATION)
public interface DubboSimpleSpi {
    void sayHello();
}
