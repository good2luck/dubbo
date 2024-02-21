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

import org.apache.dubbo.common.Extension;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.beans.support.InstantiationStrategy;
import org.apache.dubbo.common.compact.Dubbo2ActivateUtils;
import org.apache.dubbo.common.compact.Dubbo2CompactUtils;
import org.apache.dubbo.common.context.Lifecycle;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.extension.support.WrapperComparator;
import org.apache.dubbo.common.lang.Prioritized;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.resource.Disposable;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ClassLoaderResourceLoader;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.NativeUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ScopeModelAccessor;
import org.apache.dubbo.rpc.model.ScopeModelAware;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.lang.ref.SoftReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.ServiceLoader.load;
import static java.util.stream.StreamSupport.stream;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOVE_VALUE_PREFIX;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.COMMON_ERROR_LOAD_EXTENSION;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_FAILED_LOAD_ENV_VARIABLE;

/**
 * {@link org.apache.dubbo.rpc.model.ApplicationModel}, {@code DubboBootstrap} and this class are
 * at present designed to be singleton or static (by itself totally static or uses some static fields).
 * So the instances returned from them are of process or classloader scope. If you want to support
 * multiple dubbo servers in a single process, you may need to refactor these three classes.
 * <p>
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(ExtensionLoader.class);

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");
    private static final String SPECIAL_SPI_PROPERTIES = "special_spi.properties";

    // 扩展点实现类的缓存
    private final ConcurrentMap<Class<?>, Object> extensionInstances = new ConcurrentHashMap<>(64);

    private final Class<?> type;

    private final ExtensionInjector injector;

    // 扩展Class到扩展名的缓存
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<>();

    // cachedClasses：name到扩展点实现的Class 缓存，不包括标记@Adaptive注解和Wrapper扩展类
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();

    // 标记@Activate注解的扩展缓存。name to Activate注解
    private final Map<String, Object> cachedActivates = Collections.synchronizedMap(new LinkedHashMap<>());
    // 扩展点名称 -> @Activate注解的group
    private final Map<String, Set<String>> cachedActivateGroups = Collections.synchronizedMap(new LinkedHashMap<>());
    // 扩展点名称 -> @Activate注解的value，第一维表示value的长度，第二维表示value的值(存在冒号则两个值)
    private final Map<String, String[][]> cachedActivateValues = Collections.synchronizedMap(new LinkedHashMap<>());
    // 扩展点名称到扩展点实例的映射
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();

    // 自适应的实例，如果@Adaptive标记方法，会生成类，类名：接口+$Adaptive
    // 所以只有一个实例。内部会根据URL参数选择具体的实现调用的getExtension(name)方法
    private final Holder<Object> cachedAdaptiveInstance = new Holder<>();

    // 加载配置文件时，如果实现类上为@Adaptive标记，那么会加入该缓存，该实现类会作为自适应类
    private volatile Class<?> cachedAdaptiveClass = null;

    // @SPI注解上的value值，作为默认的扩展名
    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;

    // 缓存包装类，所谓包装类就是扩展点实现了接口同时构造器入参也是该接口类型，装饰器模式
    private Set<Class<?>> cachedWrapperClasses;

    private final Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<>();

    private static volatile LoadingStrategy[] strategies = loadLoadingStrategies();

    private static final Map<String, String> specialSPILoadingStrategyMap = getSpecialSPILoadingStrategyMap();

    private static SoftReference<Map<java.net.URL, List<String>>> urlListMapCache =
            new SoftReference<>(new ConcurrentHashMap<>());

    private static final List<String> ignoredInjectMethodsDesc = getIgnoredInjectMethodsDesc();

    /**
     * Record all unacceptable exceptions when using SPI
     */
    private final Set<String> unacceptableExceptions = new ConcurrentHashSet<>();

    private final ExtensionDirector extensionDirector;
    private final List<ExtensionPostProcessor> extensionPostProcessors;
    private InstantiationStrategy instantiationStrategy;
    private final ActivateComparator activateComparator;
    private final ScopeModel scopeModel;
    private final AtomicBoolean destroyed = new AtomicBoolean();

    public static void setLoadingStrategies(LoadingStrategy... strategies) {
        if (ArrayUtils.isNotEmpty(strategies)) {
            ExtensionLoader.strategies = strategies;
        }
    }

    /**
     * Load all {@link Prioritized prioritized} {@link LoadingStrategy Loading Strategies} via {@link ServiceLoader}
     *
     * @return non-null
     * @since 2.7.7
     */
    private static LoadingStrategy[] loadLoadingStrategies() {
        return stream(load(LoadingStrategy.class).spliterator(), false).sorted().toArray(LoadingStrategy[]::new);
    }

    /**
     * some spi are implements by dubbo framework only and scan multi classloaders resources may cause
     * application startup very slow
     *
     * @return
     */
    private static Map<String, String> getSpecialSPILoadingStrategyMap() {
        Map map = new ConcurrentHashMap<>();
        Properties properties = loadProperties(ExtensionLoader.class.getClassLoader(), SPECIAL_SPI_PROPERTIES);
        map.putAll(properties);
        return map;
    }

    /**
     * Get all {@link LoadingStrategy Loading Strategies}
     *
     * @return non-null
     * @see LoadingStrategy
     * @see Prioritized
     * @since 2.7.7
     */
    public static List<LoadingStrategy> getLoadingStrategies() {
        return asList(strategies);
    }

    private static List<String> getIgnoredInjectMethodsDesc() {
        List<String> ignoreInjectMethodsDesc = new ArrayList<>();
        Arrays.stream(ScopeModelAware.class.getMethods())
                .map(ReflectUtils::getDesc)
                .forEach(ignoreInjectMethodsDesc::add);
        Arrays.stream(ExtensionAccessorAware.class.getMethods())
                .map(ReflectUtils::getDesc)
                .forEach(ignoreInjectMethodsDesc::add);
        return ignoreInjectMethodsDesc;
    }

    ExtensionLoader(Class<?> type, ExtensionDirector extensionDirector, ScopeModel scopeModel) {
        this.type = type;
        this.extensionDirector = extensionDirector;
        this.extensionPostProcessors = extensionDirector.getExtensionPostProcessors();
        initInstantiationStrategy();
        this.injector = (type == ExtensionInjector.class
                ? null
                : extensionDirector.getExtensionLoader(ExtensionInjector.class).getAdaptiveExtension());
        this.activateComparator = new ActivateComparator(extensionDirector);
        this.scopeModel = scopeModel;
    }

    private void initInstantiationStrategy() {
        instantiationStrategy = extensionPostProcessors.stream()
                .filter(extensionPostProcessor -> extensionPostProcessor instanceof ScopeModelAccessor)
                .map(extensionPostProcessor -> new InstantiationStrategy((ScopeModelAccessor) extensionPostProcessor))
                .findFirst()
                .orElse(new InstantiationStrategy());
    }

    /**
     * @see ApplicationModel#getExtensionDirector()
     * @see FrameworkModel#getExtensionDirector()
     * @see ModuleModel#getExtensionDirector()
     * @see ExtensionDirector#getExtensionLoader(java.lang.Class)
     * @deprecated get extension loader from extension director of some module.
     */
    @Deprecated
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        return ApplicationModel.defaultModel().getDefaultModule().getExtensionLoader(type);
    }

    @Deprecated
    public static void resetExtensionLoader(Class type) {}

    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        // destroy raw extension instance
        extensionInstances.forEach((type, instance) -> {
            if (instance instanceof Disposable) {
                Disposable disposable = (Disposable) instance;
                try {
                    disposable.destroy();
                } catch (Exception e) {
                    logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "", "Error destroying extension " + disposable, e);
                }
            }
        });
        extensionInstances.clear();

        // destroy wrapped extension instance
        for (Holder<Object> holder : cachedInstances.values()) {
            Object wrappedInstance = holder.get();
            if (wrappedInstance instanceof Disposable) {
                Disposable disposable = (Disposable) wrappedInstance;
                try {
                    disposable.destroy();
                } catch (Exception e) {
                    logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "", "Error destroying extension " + disposable, e);
                }
            }
        }
        cachedInstances.clear();
    }

    private void checkDestroyed() {
        if (destroyed.get()) {
            throw new IllegalStateException("ExtensionLoader is destroyed: " + type);
        }
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses(); // load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        String value = url.getParameter(key);
        return getActivateExtension(url, StringUtils.isEmpty(value) ? null : COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     * 核心方法，重载的方法都会调用这个方法
     *
     * @param url    url，请求的url
     * @param values extension point names，扩展点的名字，url中获取，可能为null
     * @param group  group，分组，可能为null
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */
    @SuppressWarnings("deprecation")
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        checkDestroyed();
        // solve the bug of using @SPI's wrapper method to report a null pointer exception.
        // 用于排序的map，临时存储
        Map<Class<?>, T> activateExtensionsMap = new TreeMap<>(activateComparator);
        List<String> names = values == null
                ? new ArrayList<>(0)
                : Arrays.stream(values).map(StringUtils::trim).collect(Collectors.toList());
        // value值集合，为啥要name命名，应该是因为这个值是扩展点的名字
        Set<String> namesSet = new HashSet<>(names);

        // 不包括 -default 的值情况，这一步拿到所有default的扩展点（不在namesSet中的其它的），后面用
        if (!namesSet.contains(REMOVE_VALUE_PREFIX + DEFAULT_KEY)) {
            if (cachedActivateGroups.size() == 0) {
                synchronized (cachedActivateGroups) {
                    // cache all extensions
                    if (cachedActivateGroups.size() == 0) {
                        // 加载配置文件
                        getExtensionClasses();

                        // 遍历所有的标有@Activate注解的信息（每个类的@Activate注解）
                        for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                            // name为扩展点的名字，activate为扩展点的注解信息
                            String name = entry.getKey();
                            Object activate = entry.getValue();

                            // 注解配置的分组和值
                            String[] activateGroup, activateValue;

                            if (activate instanceof Activate) {
                                activateGroup = ((Activate) activate).group();
                                activateValue = ((Activate) activate).value();
                            } else if (Dubbo2CompactUtils.isEnabled()
                                    && Dubbo2ActivateUtils.isActivateLoaded()
                                    && Dubbo2ActivateUtils.getActivateClass().isAssignableFrom(activate.getClass())) {
                                activateGroup = Dubbo2ActivateUtils.getGroup((Annotation) activate);
                                activateValue = Dubbo2ActivateUtils.getValue((Annotation) activate);
                            } else {
                                continue;
                            }
                            // 扩展点的名字对应的group配置
                            cachedActivateGroups.put(name, new HashSet<>(Arrays.asList(activateGroup)));

                            // 根据注解配置的value内容，创建不规则的（或称为“锯齿形”）二维数组
                            String[][] keyPairs = new String[activateValue.length][];
                            for (int i = 0; i < activateValue.length; i++) {
                                if (activateValue[i].contains(":")) {
                                    keyPairs[i] = new String[2];
                                    String[] arr = activateValue[i].split(":");
                                    keyPairs[i][0] = arr[0];
                                    keyPairs[i][1] = arr[1];
                                } else {
                                    keyPairs[i] = new String[1];
                                    keyPairs[i][0] = activateValue[i];
                                }
                            }
                            // 扩展点的名字对应的value配置
                            cachedActivateValues.put(name, keyPairs);
                        }
                    }
                }
            }

            // traverse all cached extensions
            cachedActivateGroups.forEach((name, activateGroup) -> {
                // 如果入参group为空，或者注解group不为空且包含入参group 且 name 和 -name 不在入参value中 且 注解配置的value是否和url匹配（存在）
                // so，作为默认的扩展点的重要条件：1)、group匹配上、2）、扩展点的名称不在入参values中；3)、注解的value值（key或key-value）和url能匹配上
                if (isMatchGroup(group, activateGroup)
                        && !namesSet.contains(name)
                        && !namesSet.contains(REMOVE_VALUE_PREFIX + name)
                        && isActive(cachedActivateValues.get(name), url)) {
                    // 获取对应扩展点，和普通的获取方式一样。
                    activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
                }
            });
        }

        // 包括 default 的值，注意顺序
        if (namesSet.contains(DEFAULT_KEY)) {
            // will affect order：影响排序
            // `ext1,default,ext2` means ext1 will happens before all of the default extensions while ext2 will after
            // them
            // ArrayList有序集合
            ArrayList<T> extensionsResult = new ArrayList<>(activateExtensionsMap.size() + names.size());
            for (String name : names) {
                // 扩展name 以-开头。这里的namesSet判断有点冗余了。。。
                if (name.startsWith(REMOVE_VALUE_PREFIX) || namesSet.contains(REMOVE_VALUE_PREFIX + name)) {
                    continue;
                }
                // name为default，直接添加所有的扩展点
                if (DEFAULT_KEY.equals(name)) {
                    extensionsResult.addAll(activateExtensionsMap.values());
                    continue;
                }
                // 存在扩展点，添加
                if (containsExtension(name)) {
                    extensionsResult.add(getExtension(name));
                }
            }
            return extensionsResult;
        } else {
            // 不包括 default 的值，使用activateExtensionsMap对应的顺序
            // add extensions, will be sorted by its order
            for (String name : names) {
                // 扩展name 以-开头，跳过
                if (name.startsWith(REMOVE_VALUE_PREFIX) || namesSet.contains(REMOVE_VALUE_PREFIX + name)) {
                    continue;
                }
                // 进不来
                if (DEFAULT_KEY.equals(name)) {
                    continue;
                }
                // 存在扩展点，添加
                if (containsExtension(name)) {
                    activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
                }
            }
            return new ArrayList<>(activateExtensionsMap.values());
        }
    }

    /**
     * 获取{@link ExtensionLoader#type}类型接口的全部标记@Activate的扩展实现
     * @return
     */
    public List<T> getActivateExtensions() {
        checkDestroyed();
        List<T> activateExtensions = new ArrayList<>();
        // 排序map，不需要排序的话可以不用map了
        TreeMap<Class<?>, T> activateExtensionsMap = new TreeMap<>(activateComparator);
        // 加载配置
        getExtensionClasses();
        // 存在@Activate注解的扩展
        for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
            String name = entry.getKey();
            Object activate = entry.getValue();
            if (!(activate instanceof Activate)) {
                continue;
            }
            // class -> 扩展实现 map
            activateExtensionsMap.put(getExtensionClass(name), getExtension(name));
        }
        if (!activateExtensionsMap.isEmpty()) {
            activateExtensions.addAll(activateExtensionsMap.values());
        }
        // 返回全部标记@Activate的扩展实现
        return activateExtensions;
    }

    private boolean isMatchGroup(String group, Set<String> groups) {
        if (StringUtils.isEmpty(group)) {
            return true;
        }
        if (CollectionUtils.isNotEmpty(groups)) {
            return groups.contains(group);
        }
        return false;
    }

    /**
     * 注解value配置的内容是否满足
     *
     * @param keyPairs：注解配置的value内容
     * @param url：入参url
     * @return
     */
    private boolean isActive(String[][] keyPairs, URL url) {
        // 未配置
        if (keyPairs.length == 0) {
            return true;
        }
        // @Active(value="key1:value1, key2:value2")
        for (String[] keyPair : keyPairs) {
            String key;
            String keyValue = null;
            if (keyPair.length > 1) {
                key = keyPair[0];
                keyValue = keyPair[1];
            } else {
                key = keyPair[0];
            }
            // url中的key对应的value
            String realValue = url.getParameter(key);
            if (StringUtils.isEmpty(realValue)) {
                realValue = url.getAnyMethodParameter(key);
            }
            // 假设注解@Active(value="key1:value1, key2")，下面满足1个即可
            // 如果url包括key1，那么key1对应的value必须和value1相等
            // 如果url包括key2，那么key2对应的value必须不为空
            if ((keyValue != null && keyValue.equals(realValue))
                    || (keyValue == null && ConfigUtils.isNotEmpty(realValue))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        checkDestroyed();
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = getOrCreateHolder(name);
        return (T) holder.get();
    }

    private Holder<Object> getOrCreateHolder(String name) {
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return holder;
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    @SuppressWarnings("unchecked")
    public List<T> getLoadedExtensionInstances() {
        checkDestroyed();
        List<T> instances = new ArrayList<>();
        cachedInstances.values().forEach(holder -> instances.add((T) holder.get()));
        return instances;
    }

    /**
     * Find the extension with the given name.
     *
     * @throws IllegalStateException If the specified extension is not found.
     */
    public T getExtension(String name) {
        // 通过扩展名获取扩展实例，传入true表示是否需要包装
        T extension = getExtension(name, true);
        if (extension == null) {
            throw new IllegalArgumentException("Not find extension: " + name);
        }
        return extension;
    }

    /**
     * Get the extension by specified name if found, or {@link #getDefaultExtension() returns the default one}
     *
     * @param name the name of extension，自定义的扩展名称，对应着配置文件中行的key
     * @return non-null
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name, boolean wrap) {
        checkDestroyed();
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        // 如果传入的name为true，则返回默认的扩展实例，@SPI注解中的value值
        if ("true".equals(name)) {
            // 获取默认的扩展实例
            return getDefaultExtension();
        }
        String cacheKey = name;
        if (!wrap) {
            cacheKey += "_origin";
        }
        // 从缓存中获取扩展实例，并将holder存入cachedInstances缓存中，cachedInstances:ConcurrentMap<String, Holder<Object>>
        final Holder<Object> holder = getOrCreateHolder(cacheKey);
        Object instance = holder.get();
        // 双重检查锁
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    // 核心方法：真正创建扩展实例的地方
                    instance = createExtension(name, wrap);
                    holder.set(instance);
                }
            }
        }
        // object转换为T类型
        return (T) instance;
    }

    /**
     * Get the extension by specified name if found, or {@link #getDefaultExtension() returns the default one}
     *
     * @param name the name of extension
     * @return non-null
     */
    public T getOrDefaultExtension(String name) {
        return containsExtension(name) ? getExtension(name) : getDefaultExtension();
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (StringUtils.isBlank(cachedDefaultName) || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        checkDestroyed();
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    public Set<String> getSupportedExtensions() {
        checkDestroyed();
        Map<String, Class<?>> classes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<>(classes.keySet()));
    }

    public Set<T> getSupportedExtensionInstances() {
        checkDestroyed();
        List<T> instances = new LinkedList<>();
        Set<String> supportedExtensions = getSupportedExtensions();
        if (CollectionUtils.isNotEmpty(supportedExtensions)) {
            for (String name : supportedExtensions) {
                instances.add(getExtension(name));
            }
        }
        // sort the Prioritized instances
        instances.sort(Prioritized.COMPARATOR);
        return new LinkedHashSet<>(instances);
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        checkDestroyed();
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " + clazz + " doesn't implement the Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " + clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " + name + " already exists (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already exists (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        checkDestroyed();
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " + clazz + " doesn't implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " + clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " + name + " doesn't exist (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension doesn't exist (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /**
     * 获取自适应扩展点
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        checkDestroyed();
        // 先从缓存中取
        Object instance = cachedAdaptiveInstance.get();
        // 双重检查锁
        if (instance == null) {
            if (createAdaptiveInstanceError != null) {
                throw new IllegalStateException(
                        "Failed to create adaptive instance: " + createAdaptiveInstanceError.toString(),
                        createAdaptiveInstanceError);
            }

            synchronized (cachedAdaptiveInstance) {
                instance = cachedAdaptiveInstance.get();
                if (instance == null) {
                    try {
                        // 创建自适应扩展点实现
                        instance = createAdaptiveExtension();
                        // 设置到缓存中
                        cachedAdaptiveInstance.set(instance);
                    } catch (Throwable t) {
                        createAdaptiveInstanceError = t;
                        throw new IllegalStateException("Failed to create adaptive instance: " + t.toString(), t);
                    }
                }
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);

        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().startsWith(name.toLowerCase())) {
                if (i == 1) {
                    buf.append(", possible causes: ");
                }
                buf.append("\r\n(");
                buf.append(i++);
                buf.append(") ");
                buf.append(entry.getKey());
                buf.append(":\r\n");
                buf.append(StringUtils.toString(entry.getValue()));
            }
        }

        if (i == 1) {
            buf.append(", no related exception was found, please check whether related SPI module is missing.");
        }
        return new IllegalStateException(buf.toString());
    }

    /**
     * 核心方法，创建扩展实例
     *
     * @param name：自定义的扩展名称，对应着配置文件中行的key
     * @param wrap：是否包装
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name, boolean wrap) {
        // 获取接口的全部扩展类实现class，然后根据name获取对应的class
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null || unacceptableExceptions.contains(name)) {
            throw findException(name);
        }
        try {
            // 从缓存中获取扩展实例，extensionInstances:ConcurrentMap<Class<?>, Object>
            T instance = (T) extensionInstances.get(clazz);
            if (instance == null) {
                // 核心：调用createExtensionInstance方法创建扩展实例(构造器反射)，并放入缓存
                extensionInstances.putIfAbsent(clazz, createExtensionInstance(clazz));

                instance = (T) extensionInstances.get(clazz);
                // 前置处理，类似Spring的BeanPostProcessor#postProcessBeforeInitialization，可以在此处做一些扩展实例的前置处理
                instance = postProcessBeforeInitialization(instance, name);
                // DI，注入扩展实例的依赖，类似Spring的Autowired，只不过它是"setter注入"（Inject spi extension 和 scope bean）
                injectExtension(instance);
                // 后置处理，类似Spring的BeanPostProcessor#postProcessAfterInitialization，可以在此处做一些扩展实例的后置处理
                // 默认会注入实现了ScopeModelAware接口的方法
                instance = postProcessAfterInitialization(instance, name);
            }

            // 是否需要包装，默认为true
            if (wrap) {
                List<Class<?>> wrapperClassesList = new ArrayList<>();
                // 存在缓存的包装类，在getExtensionClasses()文件加载的时候会存入cachedWrapperClasses
                if (cachedWrapperClasses != null) {
                    wrapperClassesList.addAll(cachedWrapperClasses);
                    // 排序 并 反转
                    wrapperClassesList.sort(WrapperComparator.COMPARATOR);
                    Collections.reverse(wrapperClassesList);
                }

                if (CollectionUtils.isNotEmpty(wrapperClassesList)) {
                    // AOP思想的体现
                    for (Class<?> wrapperClass : wrapperClassesList) {
                        Wrapper wrapper = wrapperClass.getAnnotation(Wrapper.class);
                        // match两种情况为true：1）没有Wrapper注解，2）matches为空或者matches包含name，且mismatches不包含name
                        boolean match = (wrapper == null)
                                || ((ArrayUtils.isEmpty(wrapper.matches())
                                                || ArrayUtils.contains(wrapper.matches(), name))
                                        && !ArrayUtils.contains(wrapper.mismatches(), name));
                        if (match) {
                            // 包装扩展实例，实例化包装类，并完成"包装类"的DI，类似Spring的Autowired
                            instance = injectExtension(
                                    (T) wrapperClass.getConstructor(type).newInstance(instance));
                            // 包装类的后置处理，类似Spring的BeanPostProcessor#postProcessAfterInitialization
                            // 默认会注入实现了ScopeModelAware接口的方法
                            instance = postProcessAfterInitialization(instance, name);
                        }
                    }
                }
            }

            // Warning: After an instance of Lifecycle is wrapped by cachedWrapperClasses, it may not still be Lifecycle
            // instance, this application may not invoke the lifecycle.initialize hook.
            // 如果扩展实现了Lifecycle接口，调用其初始化方法
            initExtension(instance);
            // 返回扩展实例
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "Extension instance (name: " + name + ", class: " + type + ") couldn't be instantiated: "
                            + t.getMessage(),
                    t);
        }
    }

    private Object createExtensionInstance(Class<?> type) throws ReflectiveOperationException {
        return instantiationStrategy.instantiate(type);
    }

    @SuppressWarnings("unchecked")
    private T postProcessBeforeInitialization(T instance, String name) throws Exception {
        if (extensionPostProcessors != null) {
            for (ExtensionPostProcessor processor : extensionPostProcessors) {
                instance = (T) processor.postProcessBeforeInitialization(instance, name);
            }
        }
        return instance;
    }

    @SuppressWarnings("unchecked")
    private T postProcessAfterInitialization(T instance, String name) throws Exception {
        if (instance instanceof ExtensionAccessorAware) {
            ((ExtensionAccessorAware) instance).setExtensionAccessor(extensionDirector);
        }
        if (extensionPostProcessors != null) {
            for (ExtensionPostProcessor processor : extensionPostProcessors) {
                instance = (T) processor.postProcessAfterInitialization(instance, name);
            }
        }
        return instance;
    }

    private boolean containsExtension(String name) {
        return getExtensionClasses().containsKey(name);
    }

    /**
     * 注入扩展实例的依赖，类似Spring的Autowired
     * 使用public setter注入，同时set方法对应的属性实例可以通过injector获取
     * 不会进行依赖注入的情况：
     *  1）、对于DisableInject注解的属性不进行注入
     *  2）、对于ScopeModelAware和ExtensionAccessorAware的set方法不进行注入（因为它们是通过postProcessAfterInitialization方法注入的）
     *
     * @param instance
     * @return
     */
    private T injectExtension(T instance) {
        if (injector == null) {
            return instance;
        }

        try {
            for (Method method : instance.getClass().getMethods()) {
                if (!isSetter(method)) {
                    continue;
                }
                /**
                 * Check {@link DisableInject} to see if we need auto-injection for this property
                 */
                if (method.isAnnotationPresent(DisableInject.class)) {
                    continue;
                }

                // When spiXXX implements ScopeModelAware, ExtensionAccessorAware,
                // the setXXX of ScopeModelAware and ExtensionAccessorAware does not need to be injected
                if (method.getDeclaringClass() == ScopeModelAware.class) {
                    continue;
                }
                if (instance instanceof ScopeModelAware || instance instanceof ExtensionAccessorAware) {
                    if (ignoredInjectMethodsDesc.contains(ReflectUtils.getDesc(method))) {
                        continue;
                    }
                }

                Class<?> pt = method.getParameterTypes()[0];
                if (ReflectUtils.isPrimitives(pt)) {
                    continue;
                }

                try {
                    String property = getSetterProperty(method);
                    // 默认两种：
                    // ScopeBeanExtensionInjector：Inject scope bean
                    // SpiExtensionInjector：Inject spi extension
                    Object object = injector.getInstance(pt, property);
                    if (object != null) {
                        method.invoke(instance, object);
                    }
                } catch (Exception e) {
                    logger.error(
                            COMMON_ERROR_LOAD_EXTENSION,
                            "",
                            "",
                            "Failed to inject via method " + method.getName() + " of interface " + type.getName() + ": "
                                    + e.getMessage(),
                            e);
                }
            }
        } catch (Exception e) {
            logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "", e.getMessage(), e);
        }
        return instance;
    }

    private void initExtension(T instance) {
        if (instance instanceof Lifecycle) {
            Lifecycle lifecycle = (Lifecycle) instance;
            lifecycle.initialize();
        }
    }

    /**
     * get properties name for setter, for instance: setVersion, return "version"
     * <p>
     * return "", if setter name with length less than 3
     */
    private String getSetterProperty(Method method) {
        return method.getName().length() > 3
                ? method.getName().substring(3, 4).toLowerCase()
                        + method.getName().substring(4)
                : "";
    }

    /**
     * return true if and only if:
     * <p>
     * 1, public
     * <p>
     * 2, name starts with "set"
     * <p>
     * 3, only has one parameter
     */
    private boolean isSetter(Method method) {
        return method.getName().startsWith("set")
                && method.getParameterTypes().length == 1
                && Modifier.isPublic(method.getModifiers());
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }

    /**
     * 获取当前接口的全部的扩展类Class，并缓存
     * 也是个核心方法，从配置中获取Class
     *
     * @return
     */
    private Map<String, Class<?>> getExtensionClasses() {
        // cachedClasses扩展点实现的Class缓存，不包括标记@Adaptive注解和Wrapper扩展类
        Map<String, Class<?>> classes = cachedClasses.get();
        // 双检锁
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    try {
                        // 获取当前接口的全部的扩展类Class
                        classes = loadExtensionClasses();
                    } catch (InterruptedException e) {
                        logger.error(
                                COMMON_ERROR_LOAD_EXTENSION,
                                "",
                                "",
                                "Exception occurred when loading extension class (interface: " + type + ")",
                                e);
                        throw new IllegalStateException(
                                "Exception occurred when loading extension class (interface: " + type + ")", e);
                    }
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * synchronized in getExtensionClasses
     */
    @SuppressWarnings("deprecation")
    private Map<String, Class<?>> loadExtensionClasses() throws InterruptedException {
        checkDestroyed();
        // @SPI注解的value值，作为默认的扩展名，存入变量cachedDefaultName:String中
        cacheDefaultExtensionName();

        Map<String, Class<?>> extensionClasses = new HashMap<>();

        // 加载扩展类的策略，有三种：META-INF/dubbo/internal、META-INF/dubbo、META-INF/services
        for (LoadingStrategy strategy : strategies) {
            loadDirectory(extensionClasses, strategy, type.getName());

            // compatible with old ExtensionFactory
            if (this.type == ExtensionInjector.class) {
                loadDirectory(extensionClasses, strategy, ExtensionFactory.class.getName());
            }
        }

        return extensionClasses;
    }

    /**
     * 加载指定目录下的扩展类
     *
     * @param extensionClasses：存储加载的扩展类的Map
     * @param strategy：加载策略，不同的目录
     * @param type：扩展点接口的全限定名
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses, LoadingStrategy strategy, String type)
            throws InterruptedException {
        loadDirectoryInternal(extensionClasses, strategy, type);
        if (Dubbo2CompactUtils.isEnabled()) {
            try {
                String oldType = type.replace("org.apache", "com.alibaba");
                if (oldType.equals(type)) {
                    return;
                }
                // if class not found,skip try to load resources
                ClassUtils.forName(oldType);
                loadDirectoryInternal(extensionClasses, strategy, oldType);
            } catch (ClassNotFoundException classNotFoundException) {

            }
        }
    }

    /**
     * extract and cache default extension name if exists
     */
    private void cacheDefaultExtensionName() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation == null) {
            return;
        }

        String value = defaultAnnotation.value();
        if ((value = value.trim()).length() > 0) {
            String[] names = NAME_SEPARATOR.split(value);
            if (names.length > 1) {
                throw new IllegalStateException("More than 1 default extension name on extension " + type.getName()
                        + ": " + Arrays.toString(names));
            }
            if (names.length == 1) {
                cachedDefaultName = names[0];
            }
        }
    }

    private void loadDirectoryInternal(
            Map<String, Class<?>> extensionClasses, LoadingStrategy loadingStrategy, String type)
            throws InterruptedException {
        // 目录 + 接口全限定名
        String fileName = loadingStrategy.directory() + type;
        try {
            List<ClassLoader> classLoadersToLoad = new LinkedList<>();

            // try to load from ExtensionLoader's ClassLoader first
            if (loadingStrategy.preferExtensionClassLoader()) {
                ClassLoader extensionLoaderClassLoader = ExtensionLoader.class.getClassLoader();
                if (ClassLoader.getSystemClassLoader() != extensionLoaderClassLoader) {
                    classLoadersToLoad.add(extensionLoaderClassLoader);
                }
            }

            if (specialSPILoadingStrategyMap.containsKey(type)) {
                String internalDirectoryType = specialSPILoadingStrategyMap.get(type);
                // skip to load spi when name don't match
                if (!LoadingStrategy.ALL.equals(internalDirectoryType)
                        && !internalDirectoryType.equals(loadingStrategy.getName())) {
                    return;
                }
                classLoadersToLoad.clear();
                classLoadersToLoad.add(ExtensionLoader.class.getClassLoader());
            } else {
                // load from scope model，进入此处，存在
                Set<ClassLoader> classLoaders = scopeModel.getClassLoaders();

                if (CollectionUtils.isEmpty(classLoaders)) {
                    Enumeration<java.net.URL> resources = ClassLoader.getSystemResources(fileName);
                    if (resources != null) {
                        while (resources.hasMoreElements()) {
                            loadResource(
                                    extensionClasses,
                                    null,
                                    resources.nextElement(),
                                    loadingStrategy.overridden(),
                                    loadingStrategy.includedPackages(),
                                    loadingStrategy.excludedPackages(),
                                    loadingStrategy.onlyExtensionClassLoaderPackages());
                        }
                    }
                } else {
                    classLoadersToLoad.addAll(classLoaders);
                }
            }

            // 进入此处，按照ClassLoader加载
            Map<ClassLoader, Set<java.net.URL>> resources =
                    ClassLoaderResourceLoader.loadResources(fileName, classLoadersToLoad);
            resources.forEach(((classLoader, urls) -> {
                loadFromClass(
                        extensionClasses,
                        loadingStrategy.overridden(),
                        urls,
                        classLoader,
                        loadingStrategy.includedPackages(),
                        loadingStrategy.excludedPackages(),
                        loadingStrategy.onlyExtensionClassLoaderPackages());
            }));
        } catch (InterruptedException e) {
            throw e;
        } catch (Throwable t) {
            logger.error(
                    COMMON_ERROR_LOAD_EXTENSION,
                    "",
                    "",
                    "Exception occurred when loading extension class (interface: " + type + ", description file: "
                            + fileName + ").",
                    t);
        }
    }

    /**
     * 加载指定目录下的扩展类
     *
     * @param extensionClasses：存储加载的扩展类的Map
     * @param overridden：是否覆盖
     * @param urls：资源URL集合
     * @param classLoader：类加载器
     * @param includedPackages：包含的包
     * @param excludedPackages：排除的包
     * @param onlyExtensionClassLoaderPackages：只有扩展类加载器的包
     */
    private void loadFromClass(
            Map<String, Class<?>> extensionClasses,
            boolean overridden,
            Set<java.net.URL> urls,
            ClassLoader classLoader,
            String[] includedPackages,
            String[] excludedPackages,
            String[] onlyExtensionClassLoaderPackages) {
        if (CollectionUtils.isNotEmpty(urls)) {
            for (java.net.URL url : urls) {
                loadResource(
                        extensionClasses,
                        classLoader,
                        url,
                        overridden,
                        includedPackages,
                        excludedPackages,
                        onlyExtensionClassLoaderPackages);
            }
        }
    }

    private void loadResource(
            Map<String, Class<?>> extensionClasses,
            ClassLoader classLoader,
            java.net.URL resourceURL,
            boolean overridden,
            String[] includedPackages,
            String[] excludedPackages,
            String[] onlyExtensionClassLoaderPackages) {
        try {
            // 读取资源文件内容，每行内容为：扩展名=扩展类全限定名
            List<String> newContentList = getResourceContent(resourceURL);
            String clazz;
            for (String line : newContentList) {
                try {
                    String name = null;
                    int i = line.indexOf('=');
                    if (i > 0) {
                        name = line.substring(0, i).trim();
                        clazz = line.substring(i + 1).trim();
                    } else {
                        clazz = line;
                    }
                    if (StringUtils.isNotEmpty(clazz)
                            && !isExcluded(clazz, excludedPackages)
                            && isIncluded(clazz, includedPackages)
                            && !isExcludedByClassLoader(clazz, classLoader, onlyExtensionClassLoaderPackages)) {
                        // 加载扩展类
                        loadClass(
                                classLoader,
                                extensionClasses,
                                resourceURL,
                                Class.forName(clazz, true, classLoader),
                                name,
                                overridden);
                    }
                } catch (Throwable t) {
                    IllegalStateException e = new IllegalStateException(
                            "Failed to load extension class (interface: " + type + ", class line: " + line + ") in "
                                    + resourceURL + ", cause: " + t.getMessage(),
                            t);
                    exceptions.put(line, e);
                }
            }
        } catch (Throwable t) {
            logger.error(
                    COMMON_ERROR_LOAD_EXTENSION,
                    "",
                    "",
                    "Exception occurred when loading extension class (interface: " + type + ", class file: "
                            + resourceURL + ") in " + resourceURL,
                    t);
        }
    }

    private List<String> getResourceContent(java.net.URL resourceURL) throws IOException {
        Map<java.net.URL, List<String>> urlListMap = urlListMapCache.get();
        if (urlListMap == null) {
            synchronized (ExtensionLoader.class) {
                if ((urlListMap = urlListMapCache.get()) == null) {
                    urlListMap = new ConcurrentHashMap<>();
                    urlListMapCache = new SoftReference<>(urlListMap);
                }
            }
        }

        List<String> contentList = urlListMap.computeIfAbsent(resourceURL, key -> {
            List<String> newContentList = new ArrayList<>();

            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(resourceURL.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        line = line.substring(0, ci);
                    }
                    line = line.trim();
                    if (line.length() > 0) {
                        newContentList.add(line);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
            return newContentList;
        });
        return contentList;
    }

    private boolean isIncluded(String className, String... includedPackages) {
        if (includedPackages != null && includedPackages.length > 0) {
            for (String includedPackage : includedPackages) {
                if (className.startsWith(includedPackage + ".")) {
                    // one match, return true
                    return true;
                }
            }
            // none matcher match, return false
            return false;
        }
        // matcher is empty, return true
        return true;
    }

    private boolean isExcluded(String className, String... excludedPackages) {
        if (excludedPackages != null) {
            for (String excludePackage : excludedPackages) {
                if (className.startsWith(excludePackage + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isExcludedByClassLoader(
            String className, ClassLoader classLoader, String... onlyExtensionClassLoaderPackages) {
        if (onlyExtensionClassLoaderPackages != null) {
            for (String excludePackage : onlyExtensionClassLoaderPackages) {
                if (className.startsWith(excludePackage + ".")) {
                    // if target classLoader is not ExtensionLoader's classLoader should be excluded
                    return !Objects.equals(ExtensionLoader.class.getClassLoader(), classLoader);
                }
            }
        }
        return false;
    }

    /**
     * 处理每一个扩展类，见入参clazz
     *
     * @param classLoader：类加载器
     * @param extensionClasses：扩展类集合，从上层一步步传递下来
     * @param resourceURL：资源URL
     * @param clazz：扩展类，Class
     * @param name：扩展名
     * @param overridden：是否覆盖，默认true
     */
    private void loadClass(
            ClassLoader classLoader,
            Map<String, Class<?>> extensionClasses,
            java.net.URL resourceURL,
            Class<?> clazz,
            String name,
            boolean overridden) {
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException(
                    "Error occurred when loading extension class (interface: " + type + ", class line: "
                            + clazz.getName() + "), class " + clazz.getName() + " is not subtype of interface.");
        }

        // clazz是否是激活的，不激活则直接返回，由@Activate的onClass属性决定
        boolean isActive = loadClassIfActive(classLoader, clazz);
        if (!isActive) {
            return;
        }

        if (clazz.isAnnotationPresent(Adaptive.class)) {
            // clazz是标有Adaptive注解，将clazz指向缓存 cachedAdaptiveClass:Class<?>
            // 几种策略都是overridden=true，可覆盖
            cacheAdaptiveClass(clazz, overridden);
        } else if (isWrapperClass(clazz)) {
            // isWrapperClass：满足构造器的要求（扩展点作为构造器的入参），将class加入缓存 cachedWrapperClasses:Set<Class<?>>
            cacheWrapperClass(clazz);
        } else {
            // 此时，==》clazz不是Adaptive，也不是Wrapper，是普通的扩展类
            if (StringUtils.isEmpty(name)) {
                // 使用类名作为扩展名
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName()
                            + " in the config " + resourceURL);
                }
            }
            // 逗号分割name，正常就一个
            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {
                // 存在@Activate注解时，缓存第一个name（一般也就一个名字）到cachedActivates:Map<String, Object>
                cacheActivateClass(clazz, names[0]);
                for (String n : names) {
                    // 缓存name到 cachedNames:ConcurrentMap<Class<?>, String>
                    cacheName(clazz, n);
                    // 重要，将class放入extensionClasses
                    saveInExtensionClass(extensionClasses, clazz, n, overridden);
                }
            }
        }
    }

    /**
     * 扩展类是否是激活的
     * 根据Activate注解指定的onClass条件决定被激活，无注解不配置则默认返回true
     *
     * @param classLoader
     * @param clazz
     * @return
     */
    private boolean loadClassIfActive(ClassLoader classLoader, Class<?> clazz) {
        Activate activate = clazz.getAnnotation(Activate.class);
        // 如果没有Activate注解，直接返回true
        if (activate == null) {
            return true;
        }
        String[] onClass = null;

        // 如果有Activate注解，获取onClass
        if (activate instanceof Activate) {
            onClass = ((Activate) activate).onClass();
        } else if (Dubbo2CompactUtils.isEnabled()
                && Dubbo2ActivateUtils.isActivateLoaded()
                && Dubbo2ActivateUtils.getActivateClass().isAssignableFrom(activate.getClass())) {
            // Dubbo2的兼容处理
            onClass = Dubbo2ActivateUtils.getOnClass(activate);
        }

        boolean isActive = true;
        // 如果onClass不为空，且onClass中的类都存在，进行全量匹配（配置的className全部ClassLoader中全存在才会返回true）
        if (null != onClass && onClass.length > 0) {
            isActive = Arrays.stream(onClass)
                    .filter(StringUtils::isNotBlank)
                    .allMatch(className -> ClassUtils.isPresent(className, classLoader));
        }
        // 表示当前类是否应该根据Activate注解指定的条件被激活。
        return isActive;
    }

    /**
     * cache name
     */
    private void cacheName(Class<?> clazz, String name) {
        if (!cachedNames.containsKey(clazz)) {
            cachedNames.put(clazz, name);
        }
    }

    /**
     * put clazz in extensionClasses
     */
    private void saveInExtensionClass(
            Map<String, Class<?>> extensionClasses, Class<?> clazz, String name, boolean overridden) {
        Class<?> c = extensionClasses.get(name);
        if (c == null || overridden) {
            extensionClasses.put(name, clazz);
        } else if (c != clazz) {
            // 相同的name配置多次，抛出异常
            // duplicate implementation is unacceptable
            unacceptableExceptions.add(name);
            String duplicateMsg = "Duplicate extension " + type.getName() + " name " + name + " on " + c.getName()
                    + " and " + clazz.getName();
            logger.error(COMMON_ERROR_LOAD_EXTENSION, "", "", duplicateMsg);
            throw new IllegalStateException(duplicateMsg);
        }
    }

    /**
     * cache Activate class which is annotated with <code>Activate</code>
     * <p>
     * for compatibility, also cache class with old alibaba Activate annotation
     */
    @SuppressWarnings("deprecation")
    private void cacheActivateClass(Class<?> clazz, String name) {
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate != null) {
            cachedActivates.put(name, activate);
        } else if (Dubbo2CompactUtils.isEnabled() && Dubbo2ActivateUtils.isActivateLoaded()) {
            // support com.alibaba.dubbo.common.extension.Activate
            Annotation oldActivate = clazz.getAnnotation(Dubbo2ActivateUtils.getActivateClass());
            if (oldActivate != null) {
                cachedActivates.put(name, oldActivate);
            }
        }
    }

    /**
     * cache Adaptive class which is annotated with <code>Adaptive</code>
     */
    private void cacheAdaptiveClass(Class<?> clazz, boolean overridden) {
        if (cachedAdaptiveClass == null || overridden) {
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            throw new IllegalStateException(
                    "More than 1 adaptive class found: " + cachedAdaptiveClass.getName() + ", " + clazz.getName());
        }
    }

    /**
     * cache wrapper class
     * <p>
     * like: ProtocolFilterWrapper, ProtocolListenerWrapper
     */
    private void cacheWrapperClass(Class<?> clazz) {
        if (cachedWrapperClasses == null) {
            cachedWrapperClasses = new ConcurrentHashSet<>();
        }
        cachedWrapperClasses.add(clazz);
    }

    /**
     * test if clazz is a wrapper class
     * <p>
     * which has Constructor with given class type as its only argument
     */
    protected boolean isWrapperClass(Class<?> clazz) {
        Constructor<?>[] constructors = clazz.getConstructors();
        for (Constructor<?> constructor : constructors) {
            if (constructor.getParameterTypes().length == 1 && constructor.getParameterTypes()[0] == type) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        Extension extension = clazz.getAnnotation(Extension.class);
        if (extension != null) {
            return extension.value();
        }

        String name = clazz.getSimpleName();
        if (name.endsWith(type.getSimpleName())) {
            name = name.substring(0, name.length() - type.getSimpleName().length());
        }
        return name.toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            // 创建自适应扩展类
            T instance = (T) getAdaptiveExtensionClass().newInstance();
            // 前置处理
            instance = postProcessBeforeInitialization(instance, null);
            // DI，setter方法注入
            injectExtension(instance);
            // 后置处理，默认处理ScopeModelAware接口的set方法
            instance = postProcessAfterInitialization(instance, null);

            // 如果是Lifecycle接口的实现类，调用初始化方法
            initExtension(instance);
            return instance;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    private Class<?> getAdaptiveExtensionClass() {
        // 加载配置文件，几种情况都一样
        getExtensionClasses();
        // 如果cachedAdaptiveClass不为空，直接返回，cachedAdaptiveClass是@Adaptive注解在了实现类上
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        // 创建自适应类
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * 创建自适应类
     * 通过AdaptiveClassCodeGenerator生成自适应类的代码，然后通过Compiler编译成Class
     */
    private Class<?> createAdaptiveExtensionClass() {
        // Adaptive Classes' ClassLoader should be the same with Real SPI interface classes' ClassLoader
        ClassLoader classLoader = type.getClassLoader();
        try {
            if (NativeUtils.isNative()) {
                return classLoader.loadClass(type.getName() + "$Adaptive");
            }
        } catch (Throwable ignore) {

        }
        // 生成自适应类的代码，然后通过Compiler编译成Class
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();
        org.apache.dubbo.common.compiler.Compiler compiler = extensionDirector
                .getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class)
                .getAdaptiveExtension();
        return compiler.compile(type, code, classLoader);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

    private static Properties loadProperties(ClassLoader classLoader, String resourceName) {
        Properties properties = new Properties();
        if (classLoader != null) {
            try {
                Enumeration<java.net.URL> resources = classLoader.getResources(resourceName);
                while (resources.hasMoreElements()) {
                    java.net.URL url = resources.nextElement();
                    Properties props = loadFromUrl(url);
                    for (Map.Entry<Object, Object> entry : props.entrySet()) {
                        String key = entry.getKey().toString();
                        if (properties.containsKey(key)) {
                            continue;
                        }
                        properties.put(key, entry.getValue().toString());
                    }
                }
            } catch (IOException ex) {
                logger.error(CONFIG_FAILED_LOAD_ENV_VARIABLE, "", "", "load properties failed.", ex);
            }
        }

        return properties;
    }

    private static Properties loadFromUrl(java.net.URL url) {
        Properties properties = new Properties();
        InputStream is = null;
        try {
            is = url.openStream();
            properties.load(is);
        } catch (IOException e) {
            // ignore
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
        return properties;
    }
}
