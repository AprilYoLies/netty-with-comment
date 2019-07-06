/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util.internal;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

public abstract class TypeParameterMatcher {

    private static final TypeParameterMatcher NOOP = new TypeParameterMatcher() {
        @Override
        public boolean match(Object msg) {
            return true;
        }
    };
    // 优先查找 Class 对应的 matcher，如果没有获取到新建一个，然后返回
    public static TypeParameterMatcher get(final Class<?> parameterType) {
        final Map<Class<?>, TypeParameterMatcher> getCache =    // 还是优先从线程本地变量中获取 Class 对应的 matcher
                InternalThreadLocalMap.get().typeParameterMatcherGetCache();

        TypeParameterMatcher matcher = getCache.get(parameterType);
        if (matcher == null) {  // 如果没有获取到
            if (parameterType == Object.class) {    // 检查是不是正在查找 Object 对应的 matcher
                matcher = NOOP;
            } else {    // 否则新建一个 ReflectiveMatcher
                matcher = new ReflectiveMatcher(parameterType); // 通过反射进行 match 操作
            }
            getCache.put(parameterType, matcher);   // 将获取的 matcher 进行缓存
        }
        // 返回获取的 matcher
        return matcher;
    }
    // 该方法主要是获取实例 object 和 typeParamName 对应的参数类型，然后根据该类型从线程本地变量中获取对应的 matcher，没有的话就新建并缓存，然后返回
    public static TypeParameterMatcher find(
            final Object object, final Class<?> parametrizedSuperclass, final String typeParamName) {
        // 获取 InternalThreadLocalMap 的 typeParameterMatcherFindCache，没有就创建一个
        final Map<Class<?>, Map<String, TypeParameterMatcher>> findCache =
                InternalThreadLocalMap.get().typeParameterMatcherFindCache();
        final Class<?> thisClass = object.getClass();
        // 获取 thisClass 对应的 String，TypeParameterMatcher 映射 map
        Map<String, TypeParameterMatcher> map = findCache.get(thisClass);
        if (map == null) {
            map = new HashMap<String, TypeParameterMatcher>();
            findCache.put(thisClass, map);  // 如果该 map 还没被设置过， 直接初始化设置一个
        }
        // 获取参数类型名对应的 TypeParameterMatcher
        TypeParameterMatcher matcher = map.get(typeParamName);
        if (matcher == null) {  // 该方法主要是获取实例 object 和 typeParamName 对应的参数类型是啥
            matcher = get(find0(object, parametrizedSuperclass, typeParamName));    // 然后根据此类型获取 matcher
            map.put(typeParamName, matcher);    // 缓存该
        }
        // 返货获取的 matcher
        return matcher;
    }
    // 该方法主要是获取实例 object 和 typeParamName 对应的参数类型
    private static Class<?> find0(
            final Object object, Class<?> parametrizedSuperclass, String typeParamName) {

        final Class<?> thisClass = object.getClass();
        Class<?> currentClass = thisClass;
        for (;;) {  // 只有在实例是 parametrizedSuperclass 的实现类情况下才进行处理
            if (currentClass.getSuperclass() == parametrizedSuperclass) {
                int typeParamIndex = -1;
                TypeVariable<?>[] typeParams = currentClass.getSuperclass().getTypeParameters();
                for (int i = 0; i < typeParams.length; i ++) {
                    if (typeParamName.equals(typeParams[i].getName())) {    // 查找对应的参数类型的索引
                        typeParamIndex = i;
                        break;
                    }
                }

                if (typeParamIndex < 0) {   // 如果没有指定的参数类型，抛出异常
                    throw new IllegalStateException(
                            "unknown type parameter '" + typeParamName + "': " + parametrizedSuperclass);
                }
                // 返回实例的参数化父类 io.netty.handler.codec.MessageToMessageDecoder<io.netty.buffer.ByteBuf>
                Type genericSuperType = currentClass.getGenericSuperclass();
                if (!(genericSuperType instanceof ParameterizedType)) {
                    return Object.class;    // 如果它不是参数化的类型，那么就认定它的参数类型为 Object 直接返回就是
                }
                // 获取参数化类的实际参数类型
                Type[] actualTypeParams = ((ParameterizedType) genericSuperType).getActualTypeArguments();
                // 根据待获取参数类型的索引，直接进行参数类型的获取
                Type actualTypeParam = actualTypeParams[typeParamIndex];
                if (actualTypeParam instanceof ParameterizedType) { // 如果获得的参数类型值任然是参数类型
                    actualTypeParam = ((ParameterizedType) actualTypeParam).getRawType();   // 那么就获取它的原始类型
                }
                if (actualTypeParam instanceof Class) { // 如果参数类型值是 Class 实例
                    return (Class<?>) actualTypeParam;  // 强转后返回
                }
                if (actualTypeParam instanceof GenericArrayType) {  // 如果参数类型是泛型数组
                    Type componentType = ((GenericArrayType) actualTypeParam).getGenericComponentType();    // 那么就用其元素的泛型类型代替
                    if (componentType instanceof ParameterizedType) {   // 如果泛型数组元素类型也是参数类型
                        componentType = ((ParameterizedType) componentType).getRawType();   // 那么就获取它的原始类型
                    }
                    if (componentType instanceof Class) {   // 如果元素类型是 Class 实例，重构后获取其 class 返回
                        return Array.newInstance((Class<?>) componentType, 0).getClass();
                    }
                }
                if (actualTypeParam instanceof TypeVariable) {  // 泛型类型是可变类型？？
                    // Resolved type parameter points to another type parameter.
                    TypeVariable<?> v = (TypeVariable<?>) actualTypeParam;
                    currentClass = thisClass;
                    if (!(v.getGenericDeclaration() instanceof Class)) {
                        return Object.class;    // 如果可变类型的泛型声明是 Class，用 Object 代替返回
                    }

                    parametrizedSuperclass = (Class<?>) v.getGenericDeclaration();
                    typeParamName = v.getName();
                    if (parametrizedSuperclass.isAssignableFrom(thisClass)) {
                        continue;
                    } else {
                        return Object.class;
                    }
                }
                // 获取泛型类型失败，抛出异常
                return fail(thisClass, typeParamName);
            }
            currentClass = currentClass.getSuperclass();
            if (currentClass == null) { // 获取泛型类型失败，抛出异常
                return fail(thisClass, typeParamName);
            }
        }
    }
    // 获取泛型类型失败，抛出异常
    private static Class<?> fail(Class<?> type, String typeParamName) {
        throw new IllegalStateException(
                "cannot determine the type of the type parameter '" + typeParamName + "': " + type);
    }

    public abstract boolean match(Object msg);

    private static final class ReflectiveMatcher extends TypeParameterMatcher {
        private final Class<?> type;

        ReflectiveMatcher(Class<?> type) {
            this.type = type;
        }

        @Override
        public boolean match(Object msg) {
            return type.isInstance(msg);
        }
    }

    TypeParameterMatcher() { }
}
