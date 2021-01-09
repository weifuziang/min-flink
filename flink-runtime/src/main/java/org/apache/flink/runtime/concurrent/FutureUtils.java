/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.concurrent;

import akka.dispatch.OnComplete;
import org.apache.flink.api.common.time.Deadline;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.util.ExecutorThreadFactory;
import org.apache.flink.runtime.util.FatalExitExceptionHandler;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.function.RunnableWithException;
import org.apache.flink.util.function.SupplierWithException;
import scala.concurrent.Future;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * A collection of utilities that expand the usage of {@link CompletableFuture}.
 */
public class FutureUtils {

	private static final CompletableFuture<Void> COMPLETED_VOID_FUTURE = CompletableFuture.completedFuture(null);

	/**
	 * Returns a completed future of type {@link Void}.
	 *
	 * @return a completed future of type {@link Void}
	 */
	public static CompletableFuture<Void> completedVoidFuture() {
		return COMPLETED_VOID_FUTURE;
	}

	// ------------------------------------------------------------------------
	//  retrying operations
	// ------------------------------------------------------------------------

	/**
	 * Retry the given operation the given number of times in case of a failure.
	 *
	 * @param operation to executed
	 * @param retries if the operation failed
	 * @param executor to use to run the futures
	 * @param <T> type of the result
	 * @return Future containing either the result of the operation or a {@link RetryException}
	 */
	public static <T> CompletableFuture<T> retry(
			final Supplier<CompletableFuture<T>> operation,
			final int retries,
			final Executor executor) {

		final CompletableFuture<T> resultFuture = new CompletableFuture<>();

		retryOperation(resultFuture, operation, retries, executor);

		return resultFuture;
	}

	/**
	 * Helper method which retries the provided operation in case of a failure.
	 *
	 * @param resultFuture to complete
	 * @param operation to retry
	 * @param retries until giving up
	 * @param executor to run the futures
	 * @param <T> type of the future's result
	 */
	private static <T> void retryOperation(
			final CompletableFuture<T> resultFuture,
			final Supplier<CompletableFuture<T>> operation,
			final int retries,
			final Executor executor) {

		if (!resultFuture.isDone()) {
			final CompletableFuture<T> operationFuture = operation.get();

			operationFuture.whenCompleteAsync(
				(t, throwable) -> {
					if (throwable != null) {
						if (throwable instanceof CancellationException) {
							resultFuture.completeExceptionally(new RetryException("Operation future was cancelled.", throwable));
						} else {
							if (retries > 0) {
								retryOperation(
									resultFuture,
									operation,
									retries - 1,
									executor);
							} else {
								resultFuture.completeExceptionally(new RetryException("Could not complete the operation. Number of retries " +
									"has been exhausted.", throwable));
							}
						}
					} else {
						resultFuture.complete(t);
					}
				},
				executor);

			resultFuture.whenComplete(
				(t, throwable) -> operationFuture.cancel(false));
		}
	}

	/**
	 * Retry the given operation with the given delay in between failures.
	 *
	 * @param operation to retry
	 * @param retries number of retries
	 * @param retryDelay delay between retries
	 * @param retryPredicate Predicate to test whether an exception is retryable
	 * @param scheduledExecutor executor to be used for the retry operation
	 * @param <T> type of the result
	 * @return Future which retries the given operation a given amount of times and delays the retry in case of failures
	 */
	public static <T> CompletableFuture<T> retryWithDelay(
			final Supplier<CompletableFuture<T>> operation,
			final int retries,
			final Time retryDelay,
			final Predicate<Throwable> retryPredicate,
			final ScheduledExecutor scheduledExecutor) {

		final CompletableFuture<T> resultFuture = new CompletableFuture<>();

		retryOperationWithDelay(
			resultFuture,
			operation,
			retries,
			retryDelay,
			retryPredicate,
			scheduledExecutor);

		return resultFuture;
	}

	/**
	 * Retry the given operation with the given delay in between failures.
	 *
	 * @param operation to retry
	 * @param retries number of retries
	 * @param retryDelay delay between retries
	 * @param scheduledExecutor executor to be used for the retry operation
	 * @param <T> type of the result
	 * @return Future which retries the given operation a given amount of times and delays the retry in case of failures
	 */
	public static <T> CompletableFuture<T> retryWithDelay(
			final Supplier<CompletableFuture<T>> operation,
			final int retries,
			final Time retryDelay,
			final ScheduledExecutor scheduledExecutor) {
		return retryWithDelay(
			operation,
			retries,
			retryDelay,
			(throwable) -> true,
			scheduledExecutor);
	}

	private static <T> void retryOperationWithDelay(
			final CompletableFuture<T> resultFuture,
			final Supplier<CompletableFuture<T>> operation,
			final int retries,
			final Time retryDelay,
			final Predicate<Throwable> retryPredicate,
			final ScheduledExecutor scheduledExecutor) {

		if (!resultFuture.isDone()) {
			final CompletableFuture<T> operationResultFuture = operation.get();

			operationResultFuture.whenComplete(
				(t, throwable) -> {
					if (throwable != null) {
						if (throwable instanceof CancellationException) {
							resultFuture.completeExceptionally(new RetryException("Operation future was cancelled.", throwable));
						} else {
							throwable = ExceptionUtils.stripExecutionException(throwable);
							if (!retryPredicate.test(throwable)) {
								resultFuture.completeExceptionally(throwable);
							} else if (retries > 0) {
								final ScheduledFuture<?> scheduledFuture = scheduledExecutor.schedule(
									(Runnable) () -> retryOperationWithDelay(resultFuture, operation, retries - 1, retryDelay, retryPredicate, scheduledExecutor),
									retryDelay.toMilliseconds(),
									TimeUnit.MILLISECONDS);

								resultFuture.whenComplete(
									(innerT, innerThrowable) -> scheduledFuture.cancel(false));
							} else {
								RetryException retryException = new RetryException(
									"Could not complete the operation. Number of retries has been exhausted.",
									throwable);
								resultFuture.completeExceptionally(retryException);
							}
						}
					} else {
						resultFuture.complete(t);
					}
				});

			resultFuture.whenComplete(
				(t, throwable) -> operationResultFuture.cancel(false));
		}
	}

	public static class RetryException extends Exception {

		private static final long serialVersionUID = 3613470781274141862L;

		public RetryException(String message) {
			super(message);
		}

		public RetryException(String message, Throwable cause) {
			super(message, cause);
		}

		public RetryException(Throwable cause) {
			super(cause);
		}
	}

	/**
	 * Times the given future out after the timeout.
	 *
	 * @param future to time out
	 * @param timeout after which the given future is timed out
	 * @param timeUnit time unit of the timeout
	 * @param <T> type of the given future
	 * @return The timeout enriched future
	 */
	public static <T> CompletableFuture<T> orTimeout(CompletableFuture<T> future, long timeout, TimeUnit timeUnit) {
		return orTimeout(future, timeout, timeUnit, Executors.directExecutor());
	}

	/**
	 * Times the given future out after the timeout.
	 *
	 * @param future to time out
	 * @param timeout after which the given future is timed out
	 * @param timeUnit time unit of the timeout
	 * @param timeoutFailExecutor executor that will complete the future exceptionally after the timeout is reached
	 * @param <T> type of the given future
	 * @return The timeout enriched future
	 */
	public static <T> CompletableFuture<T> orTimeout(
		CompletableFuture<T> future,
		long timeout,
		TimeUnit timeUnit,
		Executor timeoutFailExecutor) {

		if (!future.isDone()) {
			final ScheduledFuture<?> timeoutFuture = Delayer.delay(
				() -> timeoutFailExecutor.execute(new Timeout(future)), timeout, timeUnit);

			future.whenComplete((T value, Throwable throwable) -> {
				if (!timeoutFuture.isDone()) {
					timeoutFuture.cancel(false);
				}
			});
		}

		return future;
	}

	// ------------------------------------------------------------------------
	//  Future actions
	// ------------------------------------------------------------------------

	/**
	 * Run the given {@code RunnableFuture} if it is not done, and then retrieves its result.
	 * @param future to run if not done and get
	 * @param <T> type of the result
	 * @return the result after running the future
	 * @throws ExecutionException if a problem occurred
	 * @throws InterruptedException if the current thread has been interrupted
	 */
	public static <T> T runIfNotDoneAndGet(RunnableFuture<T> future) throws ExecutionException, InterruptedException {

		if (null == future) {
			return null;
		}

		if (!future.isDone()) {
			future.run();
		}

		return future.get();
	}

	/**
	 * Run the given action after the completion of the given future. The given future can be
	 * completed normally or exceptionally. In case of an exceptional completion the, the
	 * action's exception will be added to the initial exception.
	 *
	 * @param future to wait for its completion
	 * @param runnable action which is triggered after the future's completion
	 * @return Future which is completed after the action has completed. This future can contain an exception,
	 * if an error occurred in the given future or action.
	 */
	public static CompletableFuture<Void> runAfterwards(CompletableFuture<?> future, RunnableWithException runnable) {
		return runAfterwardsAsync(future, runnable, Executors.directExecutor());
	}

	/**
	 * Run the given action after the completion of the given future. The given future can be
	 * completed normally or exceptionally. In case of an exceptional completion the, the
	 * action's exception will be added to the initial exception.
	 *
	 * @param future to wait for its completion
	 * @param runnable action which is triggered after the future's completion
	 * @return Future which is completed after the action has completed. This future can contain an exception,
	 * if an error occurred in the given future or action.
	 */
	public static CompletableFuture<Void> runAfterwardsAsync(CompletableFuture<?> future, RunnableWithException runnable) {
		return runAfterwardsAsync(future, runnable, ForkJoinPool.commonPool());
	}

	/**
	 * Run the given action after the completion of the given future. The given future can be
	 * completed normally or exceptionally. In case of an exceptional completion the, the
	 * action's exception will be added to the initial exception.
	 *
	 * @param future to wait for its completion
	 * @param runnable action which is triggered after the future's completion
	 * @param executor to run the given action
	 * @return Future which is completed after the action has completed. This future can contain an exception,
	 * if an error occurred in the given future or action.
	 */
	public static CompletableFuture<Void> runAfterwardsAsync(
		CompletableFuture<?> future,
		RunnableWithException runnable,
		Executor executor) {
		final CompletableFuture<Void> resultFuture = new CompletableFuture<>();

		future.whenCompleteAsync(
			(Object ignored, Throwable throwable) -> {
				try {
					runnable.run();
				} catch (Throwable e) {
					throwable = ExceptionUtils.firstOrSuppressed(e, throwable);
				}

				if (throwable != null) {
					resultFuture.completeExceptionally(throwable);
				} else {
					resultFuture.complete(null);
				}
			},
			executor);

		return resultFuture;
	}

	/**
	 * Run the given asynchronous action after the completion of the given future. The given future can be
	 * completed normally or exceptionally. In case of an exceptional completion, the
	 * asynchronous action's exception will be added to the initial exception.
	 *
	 * @param future to wait for its completion
	 * @param composedAction asynchronous action which is triggered after the future's completion
	 * @return Future which is completed after the asynchronous action has completed. This future can contain
	 * an exception if an error occurred in the given future or asynchronous action.
	 */
	public static CompletableFuture<Void> composeAfterwards(
			CompletableFuture<?> future,
			Supplier<CompletableFuture<?>> composedAction) {
		final CompletableFuture<Void> resultFuture = new CompletableFuture<>();

		future.whenComplete(
			(Object outerIgnored, Throwable outerThrowable) -> {
				final CompletableFuture<?> composedActionFuture = composedAction.get();

				composedActionFuture.whenComplete(
					(Object innerIgnored, Throwable innerThrowable) -> {
						if (innerThrowable != null) {
							resultFuture.completeExceptionally(ExceptionUtils.firstOrSuppressed(innerThrowable, outerThrowable));
						} else if (outerThrowable != null) {
							resultFuture.completeExceptionally(outerThrowable);
						} else {
							resultFuture.complete(null);
						}
					});
			});

		return resultFuture;
	}

	// ------------------------------------------------------------------------
	//  composing futures
	// ------------------------------------------------------------------------

	/**
	 * Creates a future that is complete once multiple other futures completed.
	 * The future fails (completes exceptionally) once one of the futures in the
	 * conjunction fails. Upon successful completion, the future returns the
	 * collection of the futures' results.
	 *
	 * <p>The ConjunctFuture gives access to how many Futures in the conjunction have already
	 * completed successfully, via {@link ConjunctFuture#getNumFuturesCompleted()}.
	 *
	 * @param futures The futures that make up the conjunction. No null entries are allowed.
	 * @return The ConjunctFuture that completes once all given futures are complete (or one fails).
	 */
	public static <T> ConjunctFuture<Collection<T>> combineAll(Collection<? extends CompletableFuture<? extends T>> futures) {
		checkNotNull(futures, "futures");

		return new ResultConjunctFuture<>(futures);
	}

	/**
	 * Creates a future that is complete once all of the given futures have completed.
	 * The future fails (completes exceptionally) once one of the given futures
	 * fails.
	 *
	 * <p>The ConjunctFuture gives access to how many Futures have already
	 * completed successfully, via {@link ConjunctFuture#getNumFuturesCompleted()}.
	 *
	 * @param futures The futures to wait on. No null entries are allowed.
	 * @return The WaitingFuture that completes once all given futures are complete (or one fails).
	 */
	public static ConjunctFuture<Void> waitForAll(Collection<? extends CompletableFuture<?>> futures) {
		return new WaitingConjunctFuture(futures);
	}

	/**
	 * A future that is complete once multiple other futures completed. The futures are not
	 * necessarily of the same type. The ConjunctFuture fails (completes exceptionally) once
	 * one of the Futures in the conjunction fails.
	 *
	 * <p>The advantage of using the ConjunctFuture over chaining all the futures (such as via
	 * {@link CompletableFuture#thenCombine(CompletionStage, BiFunction)} )}) is that ConjunctFuture
	 * also tracks how many of the Futures are already complete.
	 */
	public abstract static class ConjunctFuture<T> extends CompletableFuture<T> {

		/**
		 * Gets the total number of Futures in the conjunction.
		 * @return The total number of Futures in the conjunction.
		 */
		public abstract int getNumFuturesTotal();

		/**
		 * Gets the number of Futures in the conjunction that are already complete.
		 * @return The number of Futures in the conjunction that are already complete
		 */
		public abstract int getNumFuturesCompleted();
	}

	/**
	 * The implementation of the {@link ConjunctFuture} which returns its Futures' result as a collection.
	 */
	private static class ResultConjunctFuture<T> extends ConjunctFuture<Collection<T>> {

		/** The total number of futures in the conjunction. */
		private final int numTotal;

		/** The number of futures in the conjunction that are already complete. */
		private final AtomicInteger numCompleted = new AtomicInteger(0);

		/** The set of collected results so far. */
		private volatile T[] results;

		/** The function that is attached to all futures in the conjunction. Once a future
		 * is complete, this function tracks the completion or fails the conjunct.
		 */
		private void handleCompletedFuture(int index, T value, Throwable throwable) {
			if (throwable != null) {
				completeExceptionally(throwable);
			} else {
				results[index] = value;

				if (numCompleted.incrementAndGet() == numTotal) {
					complete(Arrays.asList(results));
				}
			}
		}

		@SuppressWarnings("unchecked")
		ResultConjunctFuture(Collection<? extends CompletableFuture<? extends T>> resultFutures) {
			this.numTotal = resultFutures.size();
			results = (T[]) new Object[numTotal];

			if (resultFutures.isEmpty()) {
				complete(Collections.emptyList());
			}
			else {
				int counter = 0;
				for (CompletableFuture<? extends T> future : resultFutures) {
					final int index = counter;
					counter++;
					future.whenComplete((value, throwable) -> handleCompletedFuture(index, value, throwable));
				}
			}
		}

		@Override
		public int getNumFuturesTotal() {
			return numTotal;
		}

		@Override
		public int getNumFuturesCompleted() {
			return numCompleted.get();
		}
	}

	/**
	 * Implementation of the {@link ConjunctFuture} interface which waits only for the completion
	 * of its futures and does not return their values.
	 */
	private static final class WaitingConjunctFuture extends ConjunctFuture<Void> {

		/** Number of completed futures. */
		private final AtomicInteger numCompleted = new AtomicInteger(0);

		/** Total number of futures to wait on. */
		private final int numTotal;

		/** Method which increments the atomic completion counter and completes or fails the WaitingFutureImpl. */
		private void handleCompletedFuture(Object ignored, Throwable throwable) {
			if (throwable == null) {
				if (numTotal == numCompleted.incrementAndGet()) {
					complete(null);
				}
			} else {
				completeExceptionally(throwable);
			}
		}

		private WaitingConjunctFuture(Collection<? extends CompletableFuture<?>> futures) {
			this.numTotal = futures.size();

			if (futures.isEmpty()) {
				complete(null);
			} else {
				for (java.util.concurrent.CompletableFuture<?> future : futures) {
					future.whenComplete(this::handleCompletedFuture);
				}
			}
		}

		@Override
		public int getNumFuturesTotal() {
			return numTotal;
		}

		@Override
		public int getNumFuturesCompleted() {
			return numCompleted.get();
		}
	}

	public static ConjunctFuture<Void> completeAll(Collection<? extends CompletableFuture<?>> futuresToComplete) {
		return new CompletionConjunctFuture(futuresToComplete);
	}

	private static final class CompletionConjunctFuture extends ConjunctFuture<Void> {

		private final Object lock = new Object();

		private final int numFuturesTotal;

		private int futuresCompleted;

		private Throwable globalThrowable;

		private CompletionConjunctFuture(Collection<? extends CompletableFuture<?>> futuresToComplete) {
			numFuturesTotal = futuresToComplete.size();

			futuresCompleted = 0;

			globalThrowable = null;

			if (futuresToComplete.isEmpty()) {
				complete(null);
			} else {
				for (CompletableFuture<?> completableFuture : futuresToComplete) {
					completableFuture.whenComplete(this::completeFuture);
				}
			}
		}

		private void completeFuture(Object ignored, Throwable throwable) {
			synchronized (lock) {
				futuresCompleted++;

				if (throwable != null) {
					globalThrowable = ExceptionUtils.firstOrSuppressed(throwable, globalThrowable);
				}

				if (futuresCompleted == numFuturesTotal) {
					if (globalThrowable != null) {
						completeExceptionally(globalThrowable);
					} else {
						complete(null);
					}
				}
			}
		}

		@Override
		public int getNumFuturesTotal() {
			return numFuturesTotal;
		}

		@Override
		public int getNumFuturesCompleted() {
			synchronized (lock) {
				return futuresCompleted;
			}
		}
	}

	public static <T>CompletableFuture<T> completedExceptionally(Throwable cause) {
		CompletableFuture<T> result = new CompletableFuture<>();
		result.completeExceptionally(cause);

		return result;
	}

	public static <T> CompletableFuture<T> supplyAsync(SupplierWithException<T, ?> supplier, Executor executor) {
		return CompletableFuture.supplyAsync(
			() -> {
				try {
					return supplier.get();
				} catch (Throwable e) {
					throw new CompletionException(e);
				}
			},
			executor);
	}


	public static Duration toDuration(Time time) {
		return Duration.ofMillis(time.toMilliseconds());
	}

	public static <T, U extends T> CompletableFuture<T> toJava(Future<U> scalaFuture) {
		final CompletableFuture<T> result = new CompletableFuture<>();

		scalaFuture.onComplete(new OnComplete<U>() {
			@Override
			public void onComplete(Throwable failure, U success) {
				if (failure != null) {
					result.completeExceptionally(failure);
				} else {
					result.complete(success);
				}
			}
		}, Executors.directExecutionContext());

		return result;
	}

	public static <IN, OUT> CompletableFuture<OUT> thenApplyAsyncIfNotDone(
		CompletableFuture<IN> completableFuture,
		Executor executor,
		Function<? super IN, ? extends OUT> applyFun) {
		return completableFuture.isDone() ?
			completableFuture.thenApply(applyFun) :
			completableFuture.thenApplyAsync(applyFun, executor);
	}

	public static <IN> CompletableFuture<IN> whenCompleteAsyncIfNotDone(
		CompletableFuture<IN> completableFuture,
		Executor executor,
		BiConsumer<? super IN, ? super Throwable> whenCompleteFun) {
		return completableFuture.isDone() ?
			completableFuture.whenComplete(whenCompleteFun) :
			completableFuture.whenCompleteAsync(whenCompleteFun, executor);
	}

	@Nullable
	public static <T> T getWithoutException(CompletableFuture<T> future) {
		if (future.isDone() && !future.isCompletedExceptionally()) {
			try {
				return future.get();
			} catch (InterruptedException | ExecutionException ignored) {
			}
		}
		return null;
	}

	/**
	 * Runnable to complete the given future with a {@link TimeoutException}.
	 */
	private static final class Timeout implements Runnable {

		private final CompletableFuture<?> future;

		private Timeout(CompletableFuture<?> future) {
			this.future = checkNotNull(future);
		}

		@Override
		public void run() {
			future.completeExceptionally(new TimeoutException());
		}
	}

	/**
	 * Delay scheduler used to timeout futures.
	 *
	 * <p>This class creates a singleton scheduler used to run the provided actions.
	 */
	private enum Delayer {
		;
		static final ScheduledThreadPoolExecutor DELAYER = new ScheduledThreadPoolExecutor(
			1,
			new ExecutorThreadFactory("FlinkCompletableFutureDelayScheduler"));

		private static ScheduledFuture<?> delay(Runnable runnable, long delay, TimeUnit timeUnit) {
			checkNotNull(runnable);
			checkNotNull(timeUnit);

			return DELAYER.schedule(runnable, delay, timeUnit);
		}
	}

	public static void assertNoException(CompletableFuture<?> completableFuture) {
		handleUncaughtException(completableFuture, FatalExitExceptionHandler.INSTANCE);
	}

	public static void handleUncaughtException(CompletableFuture<?> completableFuture, Thread.UncaughtExceptionHandler uncaughtExceptionHandler) {
		checkNotNull(completableFuture).whenComplete((ignored, throwable) -> {
			if (throwable != null) {
				uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), throwable);
			}
		});
	}

	public static <T> void forward(CompletableFuture<T> source, CompletableFuture<T> target) {
		source.whenComplete(forwardTo(target));
	}

	public static <T> void forwardAsync(CompletableFuture<T> source, CompletableFuture<T> target, Executor executor) {
		source.whenCompleteAsync(
			forwardTo(target),
			executor);
	}

	private static <T> BiConsumer<T, Throwable> forwardTo(CompletableFuture<T> target) {
		return (value, throwable) -> {
			if (throwable != null) {
				target.completeExceptionally(throwable);
			} else {
				target.complete(value);
			}
		};
	}
}
