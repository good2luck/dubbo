package top.xudj.spi.jdk;

import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * JdkSpiDemo
 */
public class JdkSpiDemo {

    /**
     * jdk spi 调用流程：
     * 1. 通过线程上下文类加载器加载 META-INF/services/下的配置文件
     * 2. 通过配置文件中的类名实例化对象
     * 3. 调用实例化对象的方法
     */
    public static void main(String[] args) {
        ServiceLoader<JdkSimpleSpi> serviceLoader = ServiceLoader.load(JdkSimpleSpi.class);
        // 通过ServiceLoader加载实现类
        Iterator<JdkSimpleSpi> iterator = serviceLoader.iterator();
        while (iterator.hasNext()) {
            JdkSimpleSpi jdkSimpleSpi = iterator.next();
            jdkSimpleSpi.sayHello();
        }
    }

}
