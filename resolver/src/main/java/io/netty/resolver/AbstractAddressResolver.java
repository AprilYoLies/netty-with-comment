/*
 * Copyright 2015 The Netty Project
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
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.TypeParameterMatcher;
import io.netty.util.internal.UnstableApi;

import java.net.SocketAddress;
import java.nio.channels.UnsupportedAddressTypeException;
import java.util.Collections;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * A skeletal {@link AddressResolver} implementation.
 */
@UnstableApi
public abstract class AbstractAddressResolver<T extends SocketAddress> implements AddressResolver<T> {

    private final EventExecutor executor;
    private final TypeParameterMatcher matcher;

    /**
     * @param executor the {@link EventExecutor} which is used to notify the listeners of the {@link Future} returned
     *                 by {@link #resolve(SocketAddress)}
     */
    protected AbstractAddressResolver(EventExecutor executor) {
        this.executor = checkNotNull(executor, "executor");
        matcher = TypeParameterMatcher.find(this, AbstractAddressResolver.class, "T");
    }

    /**
     * @param executor the {@link EventExecutor} which is used to notify the listeners of the {@link Future} returned
     *                 by {@link #resolve(SocketAddress)}
     * @param addressType the type of the {@link SocketAddress} supported by this resolver
     */ // 缓存了 executor，构建了 matcher
    protected AbstractAddressResolver(EventExecutor executor, Class<? extends T> addressType) {
        this.executor = checkNotNull(executor, "executor");
        matcher = TypeParameterMatcher.get(addressType);
    }

    /**
     * Returns the {@link EventExecutor} which is used to notify the listeners of the {@link Future} returned
     * by {@link #resolve(SocketAddress)}.
     */
    protected EventExecutor executor() {
        return executor;
    }

    @Override   // 查看是否支持该类型的 SocketAddress
    public boolean isSupported(SocketAddress address) {
        return matcher.match(address);
    }

    @Override   // 检查 address 是否被支持，支持的情况下检查是否被解析过了
    public final boolean isResolved(SocketAddress address) {
        if (!isSupported(address)) {    // 查看是否支持该类型的 SocketAddress
            throw new UnsupportedAddressTypeException();
        }

        @SuppressWarnings("unchecked")
        final T castAddress = (T) address;  // 支持的情况下进行强转
        return doIsResolved(castAddress);   // 检查地址是否被解析过了
    }

    /**
     * Invoked by {@link #isResolved(SocketAddress)} to check if the specified {@code address} has been resolved
     * already.
     */
    protected abstract boolean doIsResolved(T address);

    @Override   // 检查是否支持被解析，然后进行解析，即看 address 的 hostname 能够被正常解析，解析的结果设置到 promise
    public final Future<T> resolve(SocketAddress address) {
        if (!isSupported(checkNotNull(address, "address"))) {   // 确保支持此 address
            // Address type not supported by the resolver
            return executor().newFailedFuture(new UnsupportedAddressTypeException());
        }

        if (isResolved(address)) {  // 如果地址被解析过了
            // Resolved already; no need to perform a lookup
            @SuppressWarnings("unchecked")
            final T cast = (T) address;
            return executor.newSucceededFuture(cast);   // 返回一个成功的 future
        }

        try {
            @SuppressWarnings("unchecked")
            final T cast = (T) address;
            final Promise<T> promise = executor().newPromise(); // 构建一个 promise，用于指示 resolve 的结果
            doResolve(cast, promise);   // 看 cast 的 hostname 能够被正常解析，解析的结果设置到 promise
            return promise; // 返回 resolve 的结果
        } catch (Exception e) {
            return executor().newFailedFuture(e);
        }
    }

    @Override
    public final Future<T> resolve(SocketAddress address, Promise<T> promise) {
        checkNotNull(address, "address");
        checkNotNull(promise, "promise");

        if (!isSupported(address)) {
            // Address type not supported by the resolver
            return promise.setFailure(new UnsupportedAddressTypeException());
        }

        if (isResolved(address)) {
            // Resolved already; no need to perform a lookup
            @SuppressWarnings("unchecked")
            final T cast = (T) address;
            return promise.setSuccess(cast);
        }

        try {
            @SuppressWarnings("unchecked")
            final T cast = (T) address;
            doResolve(cast, promise);
            return promise;
        } catch (Exception e) {
            return promise.setFailure(e);
        }
    }

    @Override
    public final Future<List<T>> resolveAll(SocketAddress address) {
        if (!isSupported(checkNotNull(address, "address"))) {
            // Address type not supported by the resolver
            return executor().newFailedFuture(new UnsupportedAddressTypeException());
        }

        if (isResolved(address)) {
            // Resolved already; no need to perform a lookup
            @SuppressWarnings("unchecked")
            final T cast = (T) address;
            return executor.newSucceededFuture(Collections.singletonList(cast));
        }

        try {
            @SuppressWarnings("unchecked")
            final T cast = (T) address;
            final Promise<List<T>> promise = executor().newPromise();
            doResolveAll(cast, promise);
            return promise;
        } catch (Exception e) {
            return executor().newFailedFuture(e);
        }
    }

    @Override
    public final Future<List<T>> resolveAll(SocketAddress address, Promise<List<T>> promise) {
        checkNotNull(address, "address");
        checkNotNull(promise, "promise");

        if (!isSupported(address)) {
            // Address type not supported by the resolver
            return promise.setFailure(new UnsupportedAddressTypeException());
        }

        if (isResolved(address)) {
            // Resolved already; no need to perform a lookup
            @SuppressWarnings("unchecked")
            final T cast = (T) address;
            return promise.setSuccess(Collections.singletonList(cast));
        }

        try {
            @SuppressWarnings("unchecked")
            final T cast = (T) address;
            doResolveAll(cast, promise);
            return promise;
        } catch (Exception e) {
            return promise.setFailure(e);
        }
    }

    /**
     * Invoked by {@link #resolve(SocketAddress)} to perform the actual name
     * resolution.
     */
    protected abstract void doResolve(T unresolvedAddress, Promise<T> promise) throws Exception;

    /**
     * Invoked by {@link #resolveAll(SocketAddress)} to perform the actual name
     * resolution.
     */
    protected abstract void doResolveAll(T unresolvedAddress, Promise<List<T>> promise) throws Exception;

    @Override
    public void close() { }
}
