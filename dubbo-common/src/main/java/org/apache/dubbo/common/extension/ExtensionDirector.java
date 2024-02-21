/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.extension;

import org.apache.dubbo.rpc.model.ScopeModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ExtensionDirector is a scoped extension loader manager.
 *
 * <p></p>
 * <p>ExtensionDirector supports multiple levels, and the child can inherit the parent's extension instances. </p>
 * <p>The way to find and create an extension instance is similar to Java classloader.</p>
 *
 * 扩展管理器支持多级，子级可以继承父级的扩展实例：优先从父级查找，如果父级没有找到，再从子级查找。
 * 查找和创建扩展实例的方式类似于Java类加载器（Java类加载器的双亲委派模型：优先从父类加载器查找，如果父类加载器没有找到，再从子类加载器查找）。
 *
 * ExtensionDirector是实现ExtensionAccessor接口的一个类，它充当扩展管理器的角色，负责协调和管理所有的扩展加载器ExtensionLoader。
 */
public class ExtensionDirector implements ExtensionAccessor {

    // 扩展加载器缓存
    private final ConcurrentMap<Class<?>, ExtensionLoader<?>> extensionLoadersMap = new ConcurrentHashMap<>(64);
    // 扩展作用域缓存
    private final ConcurrentMap<Class<?>, ExtensionScope> extensionScopeMap = new ConcurrentHashMap<>(64);
    // 父级扩展管理器，如ApplicationModel的父类扩展管理器是FrameworkModel
    private final ExtensionDirector parent;

    // scope为当前扩展管理器所属的模型类型，构造对应model时初始化
    private final ExtensionScope scope;
    private final List<ExtensionPostProcessor> extensionPostProcessors = new ArrayList<>();

    // 所属的模型实例对象
    private final ScopeModel scopeModel;
    // 是否已销毁
    private final AtomicBoolean destroyed = new AtomicBoolean();

    public ExtensionDirector(ExtensionDirector parent, ExtensionScope scope, ScopeModel scopeModel) {
        this.parent = parent;
        this.scope = scope;
        this.scopeModel = scopeModel;
    }

    public void addExtensionPostProcessor(ExtensionPostProcessor processor) {
        if (!this.extensionPostProcessors.contains(processor)) {
            this.extensionPostProcessors.add(processor);
        }
    }

    public List<ExtensionPostProcessor> getExtensionPostProcessors() {
        return extensionPostProcessors;
    }

    @Override
    public ExtensionDirector getExtensionDirector() {
        return this;
    }

    /**
     * 类似ClassLoader的双亲委派
     * 例如查找ExtensionScope.Module作用域的扩展点：
     * 1）、先ModuleScope缓存中查找，找到返回，找不到则进入2）
     * 2）、再Application缓存中找，找到返回，找不到则匹配注解作用域，匹配则创建ExtensionLoader实例，不匹配进入3）
     * 3）、再Framework缓存中找，找到返回，找不到则匹配注解作用域，匹配则创建ExtensionLoader实例，不匹配进入4）
     * 4）、最后回到了ModuleScope匹配注解作用域，匹配则创建ExtensionLoader实例，不匹配不创建，返回null
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        checkDestroyed();
        // type不能为空
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        // type必须是接口
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        // type必须有@SPI注解
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type (" + type
                    + ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }

        // 1. find in local cache，先从本地缓存中查找，extensionLoadersMap:ConcurrentMap<Class<?>, ExtensionLoader<?>>
        ExtensionLoader<T> loader = (ExtensionLoader<T>) extensionLoadersMap.get(type);

        // 从缓存中获取type对应的作用域，extensionScopeMap:ConcurrentMap<Class<?>, ExtensionScope>
        ExtensionScope scope = extensionScopeMap.get(type);
        // 如果缓存中不存在，则从SPI注解中获取作用域，并放入缓存
        if (scope == null) {
            // 默认ExtensionScope.APPLICATION
            SPI annotation = type.getAnnotation(SPI.class);
            scope = annotation.scope();
            extensionScopeMap.put(type, scope);
        }
        // 判断：如果loader为空，并且作用域是SELF，则创建一个实例
        if (loader == null && scope == ExtensionScope.SELF) {
            // create an instance in self scope
            // 创建ExtensionLoader实例，createExtensionLoader0会将loader放入extensionLoadersMap缓存
            loader = createExtensionLoader0(type);
        }

        // 2. find in parent
        if (loader == null) {
            // 如果loader为空，且存在父级扩展管理器，则先从父级扩展管理器中查找（递归）
            if (this.parent != null) {
                loader = this.parent.getExtensionLoader(type);
            }
        }

        // 3. create it
        // 父类扩展管理器中也没有找到，则创建一个实例
        // 注意：这里会先判断注解的作用域是否匹配当前层的作用域，如果作用域不匹配则不会创建
        if (loader == null) {
            loader = createExtensionLoader(type);
        }

        return loader;
    }

    /**
     * 创建扩展加载器
     *
     * @param type：扩展类型，接口
     * @param <T>：实例化的扩展
     * @return
     */
    private <T> ExtensionLoader<T> createExtensionLoader(Class<T> type) {
        ExtensionLoader<T> loader = null;
        // 当前扩展管理器的作用域与type的注解@SPI中作用域是否匹配，匹配则创建ExtensionLoader实例
        if (isScopeMatched(type)) {
            // if scope is matched, just create it，并加入缓存
            loader = createExtensionLoader0(type);
        }
        return loader;
    }

    /**
     * 创建扩展加载器，并加入缓存
     *
     * @param type：扩展类型，接口
     * @param <T>：实例化的扩展
     * @return
     */
    @SuppressWarnings("unchecked")
    private <T> ExtensionLoader<T> createExtensionLoader0(Class<T> type) {
        checkDestroyed();
        ExtensionLoader<T> loader;
        // 创建ExtensionLoader对象，并放入缓存extensionLoadersMap
        // scopeModel为当前扩展管理器所属的模型对象，如ApplicationModel、ModuleModel
        extensionLoadersMap.putIfAbsent(type, new ExtensionLoader<T>(type, this, scopeModel));
        loader = (ExtensionLoader<T>) extensionLoadersMap.get(type);
        return loader;
    }

    private boolean isScopeMatched(Class<?> type) {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        return defaultAnnotation.scope().equals(scope);
    }

    private static boolean withExtensionAnnotation(Class<?> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    public ExtensionDirector getParent() {
        return parent;
    }

    public void removeAllCachedLoader() {}

    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            for (ExtensionLoader<?> extensionLoader : extensionLoadersMap.values()) {
                extensionLoader.destroy();
            }
            extensionLoadersMap.clear();
            extensionScopeMap.clear();
            extensionPostProcessors.clear();
        }
    }

    private void checkDestroyed() {
        if (destroyed.get()) {
            throw new IllegalStateException("ExtensionDirector is destroyed");
        }
    }
}
