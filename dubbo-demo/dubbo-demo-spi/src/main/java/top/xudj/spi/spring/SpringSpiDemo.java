package top.xudj.spi.spring;

import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.List;

/**
 * SpringSpiDemo
 */
public class SpringSpiDemo {

    /**
     * Spring spi 调用流程
     * 1. 通过SpringFactoriesLoader.loadFactories方法加载SpringSimpleSpi接口的实现类
     * 2. 调用实现类的sayHello方法
     */
    public static void main(String[] args) {
        List<SpringSimpleSpi> springSimpleSpiList = SpringFactoriesLoader.loadFactories(SpringSimpleSpi.class,
                SpringSimpleSpi.class.getClassLoader());
        springSimpleSpiList.forEach(SpringSimpleSpi::sayHello);
    }

}
