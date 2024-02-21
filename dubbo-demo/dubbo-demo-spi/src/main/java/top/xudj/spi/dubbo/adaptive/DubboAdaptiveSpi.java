package top.xudj.spi.dubbo.adaptive;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

/**
 * 自适应扩展点，注解可以放类或方法上
 *  如果注解在类上，那么对应的实现类，
 *  如果注解在方法上，那么根据url中的参数来动态选择扩展点实现类
 */
@SPI(DubboAdaptiveSpiImpl1.NAME)
public interface DubboAdaptiveSpi {

    @Adaptive(value = {"adaptive", "adaptiveBack"})
    String echo(URL url, String s);


    default void defaultPlay(String s) {
        System.out.println("DubboAdaptiveSpi play");
    }

    void play(String s);

}
