package top.xudj.spi.dubbo.simple;

import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ScopeModelAware;

/**
 * DubboSimpleSpiImpl2
 */
public class DubboSimpleSpiImpl2 implements DubboSimpleSpi, ScopeModelAware {

    public static final String NAME = "dubboSimpleSpiImpl2";

    @Override
    public void sayHello() {
        System.out.println("DubboSimpleSpiImpl2:" + this + " say hello");
    }

    /**
     * 通过实现ScopeModelAware接口，可以获取到ScopeModel
     * 具体见：@see ExtensionLoader#postProcessAfterInitialization(java.lang.Object, java.lang.String)'
     * @see org.apache.dubbo.rpc.model.ScopeModelAwareExtensionProcessor#postProcessAfterInitialization(java.lang.Object, java.lang.String)
     */
    @Override
    public void setFrameworkModel(FrameworkModel frameworkModel) {
        System.out.println("DubboSimpleSpiImpl2 setFrameworkModel" + frameworkModel);
    }

}
