/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

import kotlin.Unit;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.Deferred;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.GlobalScope;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.reactor.MonoKt;
import kotlinx.coroutines.reactor.ReactorFlowKt;
import kotlinx.reflect.lite.*;
import kotlinx.reflect.lite.impl.*;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static kotlinx.reflect.lite.impl.KCallablesJvm.isAccessible;

/**
 * Utilities for working with Kotlin Coroutines.
 *
 * @author Sebastien Deleuze
 * @author Phillip Webb
 * @since 5.2
 */
public abstract class CoroutinesUtils {

	/**
	 * Convert a {@link Deferred} instance to a {@link Mono}.
	 */
	public static <T> Mono<T> deferredToMono(Deferred<T> source) {
		return MonoKt.mono(Dispatchers.getUnconfined(),
				(scope, continuation) -> source.await(continuation));
	}

	/**
	 * Convert a {@link Mono} instance to a {@link Deferred}.
	 */
	public static <T> Deferred<T> monoToDeferred(Mono<T> source) {
		return BuildersKt.async(GlobalScope.INSTANCE, Dispatchers.getUnconfined(),
				CoroutineStart.DEFAULT,
				(scope, continuation) -> MonoKt.awaitSingleOrNull(source, continuation));
	}

	/**
	 * Invoke a suspending function and converts it to {@link Mono} or
	 * {@link Flux}.
	 */
	@SuppressWarnings("deprecation")
	public static Publisher<?> invokeSuspendingFunction(Method method, Object target, Object... args) {
		KFunction<?> function = Objects.requireNonNull(ReflectJvmMapping.getKotlinFunction(method));
		if (method.isAccessible() && !isAccessible(function)) {
			KCallablesJvm.setAccessible(function, true);
		}
		Mono<Object> mono = MonoKt.mono(Dispatchers.getUnconfined(), (scope, continuation) ->
					KCallables.callSuspend(function, getSuspendedFunctionArgs(target, args), continuation))
				.filter(result -> !Objects.equals(result, Unit.INSTANCE))
				.onErrorMap(InvocationTargetException.class, InvocationTargetException::getTargetException);

		KClassifier returnType = function.getReturnType().getClassifier();
		if (returnType != null) {
			if (returnType.equals(JvmClassMappingKt.getLiteKClass(Flow.class))) {
				return mono.flatMapMany(CoroutinesUtils::asFlux);
			}
			else if (returnType.equals(JvmClassMappingKt.getLiteKClass(Mono.class))) {
				return mono.flatMap(o -> ((Mono<?>)o));
			}
			else if (returnType instanceof KClass<?> kClass &&
					Publisher.class.isAssignableFrom(JvmClassMappingKt.getJavaClass(kClass))) {
				return mono.flatMapMany(o -> ((Publisher<?>)o));
			}
		}
		return mono;
	}

	private static Object[] getSuspendedFunctionArgs(Object target, Object... args) {
		Object[] functionArgs = new Object[args.length];
		functionArgs[0] = target;
		System.arraycopy(args, 0, functionArgs, 1, args.length - 1);
		return functionArgs;
	}

	private static Flux<?> asFlux(Object flow) {
		return ReactorFlowKt.asFlux(((Flow<?>) flow));
	}

}
