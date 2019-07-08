/*
 * Copyright 2014 The Netty Project
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

package io.netty.resolver;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.Closeable;
import java.net.SocketAddress;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * Creates and manages {@link NameResolver}s so that each {@link EventExecutor} has its own resolver instance.
 */
@UnstableApi
public abstract class AddressResolverGroup<T extends SocketAddress> implements Closeable {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AddressResolverGroup.class);

    /**
     * Note that we do not use a {@link ConcurrentMap} here because it is usually expensive to instantiate a resolver.
     */
    private final Map<EventExecutor, AddressResolver<T>> resolvers =
            new IdentityHashMap<EventExecutor, AddressResolver<T>>();

    protected AddressResolverGroup() { }

    /**
     * Returns the {@link AddressResolver} associated with the specified {@link EventExecutor}. If there's no associated
     * resolved found, this method creates and returns a new resolver instance created by
     * {@link #newResolver(EventExecutor)} so that the new resolver is reused on another
     * {@link #getResolver(EventExecutor)} call with the same {@link EventExecutor}.
     */ // 该方法优先根据 executor 从缓存中获取 resolver，没有的话就新建一个，然后为该 executor 添加一个监听器，用于移除 executor 对应的缓存，最后返回新建的 resolver
    public AddressResolver<T> getResolver(final EventExecutor executor) {
        if (executor == null) {
            throw new NullPointerException("executor");
        }

        if (executor.isShuttingDown()) {
            throw new IllegalStateException("executor not accepting a task");
        }

        AddressResolver<T> r;
        synchronized (resolvers) {
            r = resolvers.get(executor);
            if (r == null) {
                final AddressResolver<T> newResolver;
                try {   // 构建 DefaultNameResolver 实例，然后通过它的  asAddressResolver 方法尝试从缓存中获取 AddressResolver，没有的话就新建一个
                    newResolver = newResolver(executor);
                } catch (Exception e) {
                    throw new IllegalStateException("failed to create a new resolver", e);
                }
                // 缓存 executor 和 resolver 的关系
                resolvers.put(executor, newResolver);
                executor.terminationFuture().addListener(new FutureListener<Object>() { // 为 executor 添加一个关闭监听器
                    @Override
                    public void operationComplete(Future<Object> future) throws Exception {
                        synchronized (resolvers) {  // 该监听器的作用是从 resolvers 缓存中移除 executor 和 resolver 的关系
                            resolvers.remove(executor);
                        }
                        newResolver.close();
                    }
                });

                r = newResolver;
            }
        }

        return r;
    }

    /**
     * Invoked by {@link #getResolver(EventExecutor)} to create a new {@link AddressResolver}.
     */
    protected abstract AddressResolver<T> newResolver(EventExecutor executor) throws Exception;

    /**
     * Closes all {@link NameResolver}s created by this group.
     */
    @Override
    @SuppressWarnings({ "unchecked", "SuspiciousToArrayCall" })
    public void close() {
        final AddressResolver<T>[] rArray;
        synchronized (resolvers) {
            rArray = (AddressResolver<T>[]) resolvers.values().toArray(new AddressResolver[0]);
            resolvers.clear();
        }

        for (AddressResolver<T> r: rArray) {
            try {
                r.close();
            } catch (Throwable t) {
                logger.warn("Failed to close a resolver:", t);
            }
        }
    }
}
