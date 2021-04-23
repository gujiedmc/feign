/**
 * Copyright 2012-2020 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign.hystrix;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommand.Setter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import feign.InvocationHandlerFactory.MethodHandler;
import feign.Target;
import feign.Util;
import rx.Completable;
import rx.Observable;
import rx.Single;
import static feign.Util.checkNotNull;

/**
 * 支持Hystrix的feign动态代理生成的invocationHandler。
 * feign默认的动态代理invocationHandler {@link feign.ReflectiveFeign.FeignInvocationHandler}。
 *
 */
final class HystrixInvocationHandler implements InvocationHandler {
  // 被代理对象详情
  private final Target<?> target;
  // 被代理对象每个方法的具体处理，会在MethodHandler里面执行真正的请求。
  private final Map<Method, MethodHandler> dispatch;
  // Hystrix降级策略工厂
  private final FallbackFactory<?> fallbackFactory; // Nullable
  // 缓存
  private final Map<Method, Method> fallbackMethodMap;
  // HystrixCommand配置 origin方法 -> HystrixCommand.Setter
  private final Map<Method, Setter> setterMethodMap;

  HystrixInvocationHandler(Target<?> target, Map<Method, MethodHandler> dispatch,
      SetterFactory setterFactory, FallbackFactory<?> fallbackFactory) {
    this.target = checkNotNull(target, "target");
    this.dispatch = checkNotNull(dispatch, "dispatch");
    this.fallbackFactory = fallbackFactory;
    this.fallbackMethodMap = toFallbackMethod(dispatch);
    this.setterMethodMap = toSetters(setterFactory, target, dispatch.keySet());
  }

  /**
   * If the method param of InvocationHandler.invoke is not accessible, i.e in a package-private
   * interface, the fallback call in hystrix command will fail cause of access restrictions. But
   * methods in dispatch are copied methods. So setting access to dispatch method doesn't take
   * effect to the method in InvocationHandler.invoke. Use map to store a copy of method to invoke
   * the fallback to bypass this and reducing the count of reflection calls.
   *
   * @return cached methods map for fallback invoking
   */
  static Map<Method, Method> toFallbackMethod(Map<Method, MethodHandler> dispatch) {
    Map<Method, Method> result = new LinkedHashMap<Method, Method>();
    for (Method method : dispatch.keySet()) {
      method.setAccessible(true);
      result.put(method, method);
    }
    return result;
  }

  /**
   * Process all methods in the target so that appropriate setters are created.
   */
  static Map<Method, Setter> toSetters(SetterFactory setterFactory,
                                       Target<?> target,
                                       Set<Method> methods) {
    Map<Method, Setter> result = new LinkedHashMap<Method, Setter>();
    for (Method method : methods) {
      method.setAccessible(true);
      result.put(method, setterFactory.create(target, method));
    }
    return result;
  }

  @Override
  public Object invoke(final Object proxy, final Method method, final Object[] args)
      throws Throwable {
    // early exit if the invoked method is from java.lang.Object
    // code is the same as ReflectiveFeign.FeignInvocationHandler
    if ("equals".equals(method.getName())) {
      try {
        Object otherHandler =
            args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
        return equals(otherHandler);
      } catch (IllegalArgumentException e) {
        return false;
      }
    } else if ("hashCode".equals(method.getName())) {
      return hashCode();
    } else if ("toString".equals(method.getName())) {
      return toString();
    }

    // 创建Command
    HystrixCommand<Object> hystrixCommand =
        new HystrixCommand<Object>(setterMethodMap.get(method)) {
          @Override
          protected Object run() throws Exception {
            try {
              // 业务逻辑直接交给 MethodHandler处理。
              return HystrixInvocationHandler.this.dispatch.get(method).invoke(args);
            } catch (Exception e) {
              throw e;
            } catch (Throwable t) {
              throw (Error) t;
            }
          }

          /**
           * 降级策略。通过{@link HystrixInvocationHandler#fallbackFactory}获取降级方法，反射执行。
           */
          @Override
          protected Object getFallback() {
            if (fallbackFactory == null) {
              return super.getFallback();
            }
            try {
              Object fallback = fallbackFactory.create(getExecutionException());
              Object result = fallbackMethodMap.get(method).invoke(fallback, args);
              if (isReturnsHystrixCommand(method)) {
                return ((HystrixCommand) result).execute();
              } else if (isReturnsObservable(method)) {
                // Create a cold Observable
                return ((Observable) result).toBlocking().first();
              } else if (isReturnsSingle(method)) {
                // Create a cold Observable as a Single
                return ((Single) result).toObservable().toBlocking().first();
              } else if (isReturnsCompletable(method)) {
                ((Completable) result).await();
                return null;
              } else if (isReturnsCompletableFuture(method)) {
                return ((Future) result).get();
              } else {
                return result;
              }
            } catch (IllegalAccessException e) {
              // shouldn't happen as method is public due to being an interface
              throw new AssertionError(e);
            } catch (InvocationTargetException | ExecutionException e) {
              // Exceptions on fallback are tossed by Hystrix
              throw new AssertionError(e.getCause());
            } catch (InterruptedException e) {
              // Exceptions on fallback are tossed by Hystrix
              Thread.currentThread().interrupt();
              throw new AssertionError(e.getCause());
            }
          }
        };

    // 根据不同的响应结果，选择不同的执行方式。
    if (Util.isDefault(method)) {
      return hystrixCommand.execute();
    } else if (isReturnsHystrixCommand(method)) {
      return hystrixCommand;
    } else if (isReturnsObservable(method)) {
      // Create a cold Observable
      return hystrixCommand.toObservable();
    } else if (isReturnsSingle(method)) {
      // Create a cold Observable as a Single
      return hystrixCommand.toObservable().toSingle();
    } else if (isReturnsCompletable(method)) {
      return hystrixCommand.toObservable().toCompletable();
    } else if (isReturnsCompletableFuture(method)) {
      return new ObservableCompletableFuture<>(hystrixCommand);
    }
    return hystrixCommand.execute();
  }

  private boolean isReturnsCompletable(Method method) {
    return Completable.class.isAssignableFrom(method.getReturnType());
  }

  private boolean isReturnsHystrixCommand(Method method) {
    return HystrixCommand.class.isAssignableFrom(method.getReturnType());
  }

  private boolean isReturnsObservable(Method method) {
    return Observable.class.isAssignableFrom(method.getReturnType());
  }

  private boolean isReturnsCompletableFuture(Method method) {
    return CompletableFuture.class.isAssignableFrom(method.getReturnType());
  }

  private boolean isReturnsSingle(Method method) {
    return Single.class.isAssignableFrom(method.getReturnType());
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof HystrixInvocationHandler) {
      HystrixInvocationHandler other = (HystrixInvocationHandler) obj;
      return target.equals(other.target);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return target.hashCode();
  }

  @Override
  public String toString() {
    return target.toString();
  }
}
