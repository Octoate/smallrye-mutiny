package io.smallrye.reactive.subscription;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import io.smallrye.reactive.helpers.ParameterValidation;
import io.smallrye.reactive.helpers.Subscriptions;

/**
 * An implementation of {@link Subscription} that allows switching the upstream, dealing with the requests accordingly.
 * <p>
 * You must invoke {@link #emitted(long)} after delivered items to manage the request per
 * subscription consistently.
 *
 * @param <O> outgoing item type
 */
@SuppressWarnings("SubscriberImplementation")
public abstract class SwitchableSubscriptionSubscriber<O> implements Subscriber<O>, Subscription {

    /**
     * The downstream subscriber
     */
    protected final Subscriber<? super O> downstream;

    /**
     * The current upstream
     */
    protected final AtomicReference<Subscription> currentUpstream = new AtomicReference<>();

    /**
     * outstanding request amount.
     */
    private long requested;

    /**
     * {@code true} if request is Long.MAX.
     */
    private boolean unbounded;

    /**
     * Pending subscription.
     */
    private final AtomicReference<Subscription> pendingSubscription = new AtomicReference<>();

    /**
     * Pending amount of request.
     */
    private final AtomicLong missedRequested = new AtomicLong();

    /**
     * Pending amount of emitted items.
     */
    private final AtomicLong missedItems = new AtomicLong();

    /**
     * Whether or not there is work in progress.
     */
    private final AtomicInteger wip = new AtomicInteger();

    /**
     * Whether or not the downstream cancelled the subscription.
     */
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public SwitchableSubscriptionSubscriber(Subscriber<? super O> downstream) {
        this.downstream = downstream;
    }

    @Override
    public void cancel() {
        if (!cancelled.getAndSet(true)) {
            drain();
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void onComplete() {
        downstream.onComplete();
    }

    @Override
    public void onError(Throwable t) {
        downstream.onError(t);
    }

    @Override
    public void onSubscribe(Subscription s) {
        setOrSwitchUpstream(s);
    }

    public void emitted(long n) {
        if (unbounded) {
            return;
        }
        if (wip.compareAndSet(0, 1)) {
            long r = requested;

            if (r != Long.MAX_VALUE) {
                long u = r - n;
                if (u < 0L) {
                    u = 0;
                }
                requested = u;
            } else {
                unbounded = true;
            }

            if (wip.decrementAndGet() == 0) {
                return;
            }

            drainLoop();

            return;
        }

        Subscriptions.add(missedItems, n);

        drain();
    }

    final void emitted() {
        if (unbounded) {
            return;
        }
        if (wip.compareAndSet(0, 1)) {
            long r = requested;

            if (r != Long.MAX_VALUE) {
                r--;
                if (r < 0L) {
                    r = 0;
                }
                requested = r;
            } else {
                unbounded = true;
            }

            if (wip.decrementAndGet() == 0) {
                return;
            }

            drainLoop();

            return;
        }

        Subscriptions.add(missedItems, 1L);

        drain();
    }

    @Override
    public final void request(long n) {
        if (n > 0) {
            if (unbounded) {
                return;
            }
            if (wip.compareAndSet(0, 1)) {
                long r = requested;

                if (r != Long.MAX_VALUE) {
                    r = Subscriptions.add(r, n);
                    requested = r;
                    if (r == Long.MAX_VALUE) {
                        unbounded = true;
                    }
                }
                Subscription actual = currentUpstream.get();

                if (wip.decrementAndGet() != 0) {
                    drainLoop();
                }

                if (actual != null) {
                    actual.request(n);
                }

                return;
            }

            Subscriptions.add(missedRequested, n);

            drain();
        }
    }

    public final void setOrSwitchUpstream(Subscription newUpstream) {
        ParameterValidation.nonNull(newUpstream, "newUpstream");

        if (cancelled.get()) {
            newUpstream.cancel();
            return;
        }

        if (wip.compareAndSet(0, 1)) {
            Subscription actual = currentUpstream.getAndSet(newUpstream);
            if (actual != null && cancelUpstreamOnSwitch()) {
                actual.cancel();
            }

            // Store the pending number of request as the drain loop may change it.
            long r = requested;

            if (wip.decrementAndGet() != 0) {
                drainLoop();
            }

            if (r != 0L) {
                newUpstream.request(r);
            }
        } else {
            Subscription actual = currentUpstream.getAndSet(newUpstream);
            if (actual != null && cancelUpstreamOnSwitch()) {
                actual.cancel();
            }
            drain();
        }
    }

    /**
     * @return @{code true} if we need to cancel the current subscription when we switch the upstreams.
     */
    protected boolean cancelUpstreamOnSwitch() {
        return false;
    }

    private void drain() {
        if (wip.getAndIncrement() != 0) {
            return;
        }
        drainLoop();
    }

    private void drainLoop() {
        int missed = 1;

        long requestAmount = 0L;
        Subscription requestTarget = null;

        for (;;) {

            Subscription nextUpstream = pendingSubscription.getAndSet(null);
            long pendingRequests = missedRequested.getAndSet(0L);
            long pendingItems = missedItems.getAndSet(0L);
            Subscription upstream = currentUpstream.get();

            if (cancelled.get()) {
                // Cancel and release all.
                if (upstream != null) {
                    upstream.cancel();
                    currentUpstream.set(null);
                }
                if (nextUpstream != null) {
                    nextUpstream.cancel();
                }
            } else {
                long req = requested;
                if (req != Long.MAX_VALUE) {
                    long res = Subscriptions.add(req, pendingRequests);

                    if (res != Long.MAX_VALUE) {
                        long remaining = res - pendingItems;
                        if (remaining < 0L) {
                            remaining = 0;
                        }
                        req = remaining;
                    } else {
                        req = res;
                    }
                    requested = req;
                }

                // Perform the switch
                if (nextUpstream != null) {
                    if (upstream != null && cancelUpstreamOnSwitch()) {
                        upstream.cancel();
                    }
                    currentUpstream.set(nextUpstream);
                    if (req != 0L) {
                        requestAmount = Subscriptions.add(requestAmount, req);
                        requestTarget = nextUpstream;
                    }
                } else if (pendingRequests != 0L && upstream != null) {
                    requestAmount = Subscriptions.add(requestAmount, pendingRequests);
                    requestTarget = upstream;
                }
            }

            missed = wip.addAndGet(-missed);
            if (missed == 0) {
                if (requestAmount != 0L) {
                    requestTarget.request(requestAmount);
                }
                return;
            }
        }
    }
}
