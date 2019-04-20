/*
 * Copyright (c) 2011-Present Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.netty.channel;

import java.nio.channels.ClosedChannelException;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.Fuseable;
import reactor.core.Scannable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.netty.FutureMono;
import reactor.util.annotation.Nullable;
import reactor.util.concurrent.Queues;
import reactor.util.context.Context;

class MonoSendMany<I, O> extends Mono<Void> implements Scannable {

	static MonoSendMany<ByteBuf, ByteBuf> byteBufSource(Publisher<? extends ByteBuf> source, Channel channel, @Nullable FlushOptions flushOption) {
		return new MonoSendMany<>(source, channel, flushOption, FUNCTION_BB_IDENTITY, CONSUMER_BB_NOCHECK_CLEANUP, CONSUMER_BB_CLEANUP, SIZE_OF_BB);
	}

	static MonoSendMany<?, ?> objectSource(Publisher<?> source, Channel channel, @Nullable FlushOptions flushOption) {
		return new MonoSendMany<>(source, channel, flushOption, FUNCTION_IDENTITY, CONSUMER_NOCHECK_CLEANUP, CONSUMER_CLEANUP, SIZE_OF);
	}

	final Publisher<? extends I> source;
	final Channel                channel;
	final Function<I, O>         transformer;
	final Consumer<O>            writeCleanup;
	final Consumer<I>            sourceCleanup;
	final ToIntFunction<O>       sizeOf;
	final FlushOptions           flushOption;

	MonoSendMany(Publisher<? extends I> source,
			Channel channel,
			@Nullable FlushOptions flushOption,
			Function<I, O> transformer,
			Consumer<I> sourceCleanup,
			Consumer<O> writeCleanup,
			ToIntFunction<O> sizeOf) {
		this.source = source;
		this.channel = channel;
		this.flushOption = flushOption == null ? FlushOptions.FLUSH_ON_BOUNDARY : flushOption;
		this.transformer = transformer;
		this.sizeOf = sizeOf;
		this.sourceCleanup = sourceCleanup;
		this.writeCleanup = writeCleanup;
	}

	@Override
	public void subscribe(CoreSubscriber<? super Void> destination) {
		source.subscribe(new SendManyInner<>(this, destination));
	}

	@Override
	@Nullable
	public Object scanUnsafe(Attr key) {
		if (key == Attr.PREFETCH) return MAX_SIZE;
		if (key == Attr.PARENT) return source;
		return null;
	}

//
	enum FlushOptions {FLUSH_ON_EACH, FLUSH_ON_BURST, FLUSH_ON_BOUNDARY}

	static final class SendManyInner<I, O> implements CoreSubscriber<I>, Subscription,
	                                                  ChannelFutureListener, Runnable, Scannable, Fuseable {

		final ChannelHandlerContext        ctx;
		final EventLoop                    eventLoop;
		final MonoSendMany<I, O>           parent;
		final CoreSubscriber<? super Void> actual;
		final Queue<I>                     queue;

		@SuppressWarnings("unused")
		volatile Subscription s;

		@SuppressWarnings("unused")
		volatile int          wip;

		boolean done;
		int     pending;
		long    requested;
//		long    produced;
		int     sourceMode;

		SendManyInner(MonoSendMany<I, O> parent, CoreSubscriber<? super Void> actual) {
			this.parent = parent;
			this.actual = actual;
			this.requested = MAX_SIZE;
			this.ctx = Objects.requireNonNull(parent.channel.pipeline().context(ChannelOperationsHandler.class));
			this.eventLoop = parent.channel.eventLoop();
			this.queue = Queues.<I>get(MAX_SIZE).get();

//			this.fuse = queue instanceof Fuseable.QueueSubscription;

			//TODO cleanup on complete
			Disposable listener =
					FutureMono.from(ctx.channel().closeFuture())
			          .doOnTerminate(() -> {
				          if (!done && SUBSCRIPTION.get(this) != Operators.cancelledSubscription()) {
					          onError(new ClosedChannelException());
				          }
			          })
			          .subscribe();
		}

		@Override
		public Context currentContext() {
			return actual.currentContext();
		}

		@Override
		public void cancel() {
			if (Operators.terminate(SUBSCRIPTION, this)) {
				return;
			}
			if (WIP.getAndIncrement(this) == 0) {
				cleanup();
			}
		}

		void cleanup() {
			while (!queue.isEmpty()) {
				I sourceMessage = queue.poll();
				if (sourceMessage != null) {
					parent.sourceCleanup.accept(sourceMessage);
				}
			}
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;
			tryDrain(null);
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t, actual.currentContext());
				return;
			}
			done = true;

			//FIXME serialize om drain loop
			cleanup();

			actual.onError(t);
		}

		@Override
		public void onNext(I t) {
			if (sourceMode == ASYNC) {
				tryDrain(null);
				return;
			}

			if (done) {
				parent.sourceCleanup.accept(t);
				Operators.onDiscard(t, actual.currentContext());
				return;
			}

			//FIXME check cancel race
			if (!queue.offer(t)) {
				onError(Operators.onOperatorError(s,
						Exceptions.failWithOverflow(Exceptions.BACKPRESSURE_ERROR_QUEUE_FULL),
						t,
						actual.currentContext()));
				return;
			}
			tryDrain(t);
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;
				actual.onSubscribe(this);
				s.request(MAX_SIZE);
				tryDrain(null);
			}
		}

		@Override
		public void request(long n) {
			//ignore since downstream has no demand
		}

		@Override
		public void operationComplete(ChannelFuture future) {
			if (future.cause() != null) {
				onError(future.cause());
			}
			else {
				requested -= pending;
				pending = 0;

				if (requested <= REFILL_SIZE) {
					long u = MAX_SIZE - requested;
					requested = Operators.addCap(requested, u);
					s.request(u);
				}

				tryComplete();
			}
		}

		@Override
		public void run() {
			try {
				boolean scheduleFlush;
				int missed = 1;
				for (; ; ) {
					scheduleFlush = false;

					long r = requested;
					ChannelPromise lastWrite;
					ChannelFuture lastFuture;

					while (r-- > 0) {
						I sourceMessage = queue.poll();
						if (sourceMessage != null && s != Operators.cancelledSubscription()) {
							O encodedMessage = parent.transformer.apply(sourceMessage);
							int readableBytes = parent.sizeOf.applyAsInt(encodedMessage);
							pending++;

							scheduleFlush =
									parent.flushOption == FlushOptions.FLUSH_ON_BURST &&
											ctx.channel().isWritable() &&
											readableBytes <= ctx.channel().bytesBeforeUnwritable();

							if (r > 0 && !queue.isEmpty()) {
								lastWrite = ctx.voidPromise();
							}
							else {
								lastWrite = ctx.newPromise();
							}

							lastFuture = ctx.write(encodedMessage, lastWrite);

							if (!scheduleFlush) {
								ctx.flush();
							}

							if (lastFuture != ctx.voidPromise()) {
								lastFuture.addListener(this);
							}
						}
						else {
							break;
						}
					}

					if (scheduleFlush) {
						try {
							ctx.flush();
							tryComplete();
						}
						catch (Throwable t) {
							onError(t);
						}
					}
					else if (done){
						if (parent.flushOption != FlushOptions.FLUSH_ON_EACH ) {
							ctx.flush();
						}
						tryComplete();
					}

					if (Operators.cancelledSubscription() == s) {
						return;
					}

					missed = WIP.addAndGet(this, -missed);
					if (missed == 0) {
						break;
					}
				}
			}
			catch (Throwable t) {
				onError(t);
			}
		}

//		void runAsync() {
//			int missed = 1;
//
//			final Queue<I> q = queue;
//			final CoreSubscriber<? super Void> a = actual;
//
//			long e = produced;
//
//			for (; ; ) {
//
//				long r = requested;
//
//				while (e != r) {
//					boolean d = done;
//					I v;
//
//					try {
//						v = q.poll();
//					}
//					catch (Throwable ex) {
//						Exceptions.throwIfFatal(ex);
//						s.cancel();
//						Operators.onDiscardQueueWithClear(q, a.currentContext(), null);
//
//						a.onError(Operators.onOperatorError(ex, a.currentContext()));
//						return;
//					}
//
//					boolean empty = v == null;
//
//					if (checkTerminated(d, empty, a)) {
//						return;
//					}
//
//					if (empty) {
//						break;
//					}
//
//					a.onNext(v);
//
//					e++;
//					if (e == REFILL_SIZE) {
//						if (r != Long.MAX_VALUE) {
//							r = Operators.subOrZero(requested, e);
//						}
//						s.request(e);
//						e = 0L;
//					}
//				}
//
//				if (e == r && checkTerminated(done, q.isEmpty(), a)) {
//					return;
//				}
//
//				int w = wip;
//				if (missed == w) {
//					produced = e;
//					missed = WIP.addAndGet(this, -missed);
//					if (missed == 0) {
//						break;
//					}
//				}
//				else {
//					missed = w;
//				}
//			}
//		}
//
//		boolean checkTerminated(boolean d, boolean empty, CoreSubscriber<? super Void> a) {
//			if (Operators.cancelledSubscription() == s) {
//				Operators.onDiscardQueueWithClear(queue, a.currentContext(), null);
//				return true;
//			}
//			if (d && empty) {
//				a.onComplete();
//				return true;
//			}
//
//			return false;
//		}

		void tryComplete() {
			if (pending == 0 && done && queue.isEmpty()
					&& SUBSCRIPTION.getAndSet(this, Operators.cancelledSubscription()) != Operators.cancelledSubscription()) {
				actual.onComplete();
			}
		}

		void tryDrain(@Nullable Object data) {
			if (WIP.getAndIncrement(this) == 0) {
				try {
					if (eventLoop.inEventLoop()) {
						run();
						return;
					}
					eventLoop.execute(this);
				}
				catch (Throwable t) {
					cleanup();
					actual.onError(Operators.onRejectedExecution(t, s, null, data, actual.currentContext()));
				}
			}
		}

		@Override
		public Object scanUnsafe(Attr key) {
			if (key == Attr.PARENT) return s;
			if (key == Attr.ACTUAL) return actual;
			if (key == Attr.REQUESTED_FROM_DOWNSTREAM) return requested;
			if (key == Attr.CANCELLED) return Operators.cancelledSubscription() == s;
			if (key == Attr.TERMINATED) return done;
			if (key == Attr.BUFFERED) return queue != null ? queue.size() : 0;
//			if (key == Attr.ERROR) return error;
			if (key == Attr.PREFETCH) return MAX_SIZE;
			return null;
		}

		static final AtomicIntegerFieldUpdater<SendManyInner>                 WIP          =
				AtomicIntegerFieldUpdater.newUpdater(SendManyInner.class, "wip");
		static final AtomicReferenceFieldUpdater<SendManyInner, Subscription> SUBSCRIPTION =
				AtomicReferenceFieldUpdater.newUpdater(SendManyInner.class, Subscription.class, "s");
	}

	static final int                    MAX_SIZE    = Queues.SMALL_BUFFER_SIZE;
	static final int                    REFILL_SIZE = MAX_SIZE / 2;
	static final ToIntFunction<ByteBuf> SIZE_OF_BB  = ByteBuf::readableBytes;
	static final ToIntFunction<Object>  SIZE_OF     = msg -> {
		if (msg instanceof ByteBufHolder) {
			return ((ByteBufHolder) msg).content()
			                            .readableBytes();
		}
		if (msg instanceof ByteBuf) {
			return ((ByteBuf) msg).readableBytes();
		}
		return 0;
	};

	static final Function<ByteBuf, ByteBuf> FUNCTION_BB_IDENTITY =
			Function.identity();
	static final Function<Object, Object>   FUNCTION_IDENTITY    =
			Function.identity();

	static final Consumer<ByteBuf> CONSUMER_BB_NOCHECK_CLEANUP = ByteBuf::release;
	static final Consumer<Object>  CONSUMER_NOCHECK_CLEANUP    =
			ReferenceCountUtil::release;

	static final Consumer<ByteBuf> CONSUMER_BB_CLEANUP = data -> {
		if (data.refCnt() > 0) {
			data.release();
		}
	};

	static final Consumer<Object> CONSUMER_CLEANUP = data -> {
		if (data instanceof ReferenceCounted) {
			ReferenceCounted counted = (ReferenceCounted) data;
			if (counted.refCnt() > 0) {
				counted.release();
			}
		}
	};
}
