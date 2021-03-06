/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.util;

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger;
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger.MergingTriggerInfo;
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger.TriggerInfo;
import com.google.cloud.dataflow.sdk.util.state.StateInternals;
import com.google.cloud.dataflow.sdk.util.state.StateNamespace;
import com.google.cloud.dataflow.sdk.util.state.StateNamespaces;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import org.joda.time.Instant;

import java.util.Collection;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Factory for creating instances of the various {@link Trigger} contexts.
 *
 * <p>These contexts are highly interdependent and share many fields; it is inadvisable
 * to create them via any means other than this factory class.
 */
public class TriggerContextFactory<W extends BoundedWindow> {

  private final WindowingStrategy<?, W> windowingStrategy;
  private StateInternals stateInternals;
  private ActiveWindowSet<W> activeWindows;

  public TriggerContextFactory(WindowingStrategy<?, W> windowingStrategy,
      StateInternals stateInternals, ActiveWindowSet<W> activeWindows) {
    this.windowingStrategy = windowingStrategy;
    this.stateInternals = stateInternals;
    this.activeWindows = activeWindows;
  }

  public Trigger<W>.TriggerContext base(W window, ReduceFn.Timers timers,
      ExecutableTrigger<W> rootTrigger, FinishedTriggers finishedSet) {
    return new TriggerContextImpl(window, timers, rootTrigger, finishedSet);
  }

  public Trigger<W>.OnElementContext createOnElementContext(
      W window, ReduceFn.Timers timers, Instant elementTimestamp,
      ExecutableTrigger<W> rootTrigger, FinishedTriggers finishedSet) {
    return new OnElementContextImpl(
        window, timers, rootTrigger, finishedSet,
        elementTimestamp);
  }

  public Trigger<W>.OnMergeContext createOnMergeContext(
      W window, ReduceFn.Timers timers, Collection<W> mergingWindows,
      ExecutableTrigger<W> rootTrigger, FinishedTriggers finishedSet,
      Map<W, FinishedTriggers> finishedSets) {
    return new OnMergeContextImpl(window, timers, rootTrigger, finishedSet,
        mergingWindows, finishedSets);
  }

  private class TriggerInfoImpl implements Trigger.TriggerInfo<W> {

    protected final ExecutableTrigger<W> trigger;
    protected final FinishedTriggers finishedSet;
    private final Trigger<W>.TriggerContext context;

    public TriggerInfoImpl(ExecutableTrigger<W> trigger, FinishedTriggers finishedSet,
        Trigger<W>.TriggerContext context) {
      this.trigger = trigger;
      this.finishedSet = finishedSet;
      this.context = context;
    }

    @Override
    public boolean isMerging() {
      return !windowingStrategy.getWindowFn().isNonMerging();
    }

    @Override
    public Iterable<ExecutableTrigger<W>> subTriggers() {
      return trigger.subTriggers();
    }

    @Override
    public ExecutableTrigger<W> subTrigger(int subtriggerIndex) {
      return trigger.subTriggers().get(subtriggerIndex);
    }

    @Override
    public boolean isFinished() {
      return finishedSet.isFinished(trigger);
    }

    @Override
    public boolean isFinished(int subtriggerIndex) {
      return finishedSet.isFinished(subTrigger(subtriggerIndex));
    }

    @Override
    public boolean areAllSubtriggersFinished() {
      return Iterables.isEmpty(unfinishedSubTriggers());
    }

    @Override
    public Iterable<ExecutableTrigger<W>> unfinishedSubTriggers() {
      return FluentIterable
          .from(trigger.subTriggers())
          .filter(new Predicate<ExecutableTrigger<W>>() {
            @Override
            public boolean apply(ExecutableTrigger<W> trigger) {
              return !finishedSet.isFinished(trigger);
            }
          });
    }

    @Override
    public ExecutableTrigger<W> firstUnfinishedSubTrigger() {
      for (ExecutableTrigger<W> subTrigger : trigger.subTriggers()) {
        if (!finishedSet.isFinished(subTrigger)) {
          return subTrigger;
        }
      }
      return null;
    }

    @Override
    public void resetTree() throws Exception {
      finishedSet.clearRecursively(trigger);
      trigger.invokeClear(context);
    }

    @Override
    public void setFinished(boolean finished) {
      finishedSet.setFinished(trigger, finished);
    }

    @Override
    public void setFinished(boolean finished, int subTriggerIndex) {
      finishedSet.setFinished(subTrigger(subTriggerIndex), finished);
    }
  }

  private class TriggerTimers implements ReduceFn.Timers {

    private final ReduceFn.Timers timers;
    private final W window;

    public TriggerTimers(W window, ReduceFn.Timers timers) {
      this.timers = timers;
      this.window = window;
    }

    @Override
    public void setTimer(Instant timestamp, TimeDomain timeDomain) {
      timers.setTimer(timestamp, timeDomain);
    }

    @Override
    public void deleteTimer(Instant timestamp, TimeDomain timeDomain) {
      if (timeDomain == TimeDomain.EVENT_TIME
          && timestamp.equals(window.maxTimestamp())) {
        // Don't allow triggers to unset the at-max-timestamp timer. This is necessary for on-time
        // state transitions.
        return;
      }
      timers.deleteTimer(timestamp, timeDomain);
    }

    @Override
    public Instant currentProcessingTime() {
      return timers.currentProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentSynchronizedProcessingTime() {
      return timers.currentSynchronizedProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentEventTime() {
      return timers.currentEventTime();
    }
  }

  private class MergingTriggerInfoImpl
      extends TriggerInfoImpl implements Trigger.MergingTriggerInfo<W> {

    private final Map<W, FinishedTriggers> finishedSets;

    public MergingTriggerInfoImpl(
        ExecutableTrigger<W> trigger,
        FinishedTriggers finishedSet,
        Trigger<W>.TriggerContext context,
        Map<W, FinishedTriggers> finishedSets) {
      super(trigger, finishedSet, context);
      this.finishedSets = finishedSets;
    }

    @Override
    public boolean finishedInAnyMergingWindow() {
      for (FinishedTriggers finishedSet : finishedSets.values()) {
        if (finishedSet.isFinished(trigger)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public boolean finishedInAllMergingWindows() {
      for (FinishedTriggers finishedSet : finishedSets.values()) {
        if (!finishedSet.isFinished(trigger)) {
          return false;
        }
      }
      return true;
    }

    @Override
    public Iterable<W> getFinishedMergingWindows() {
      return Maps.filterValues(finishedSets, new Predicate<FinishedTriggers>() {
        @Override
        public boolean apply(FinishedTriggers finishedSet) {
          return finishedSet.isFinished(trigger);
        }
      }).keySet();
    }
  }

  private ReduceFnContextFactory.StateContextImpl<W> triggerState(
      W window, ExecutableTrigger<W> trigger) {
    return new TriggerStateContextImpl(
        activeWindows, windowingStrategy.getWindowFn().windowCoder(),
        stateInternals, window, trigger);
  }

  private class TriggerStateContextImpl
      extends ReduceFnContextFactory.StateContextImpl<W> {

    private int triggerIndex;

    public TriggerStateContextImpl(ActiveWindowSet<W> activeWindows,
        Coder<W> windowCoder, StateInternals stateInternals, W window,
        ExecutableTrigger<W> trigger) {
      super(activeWindows, windowCoder, stateInternals, window);
      this.triggerIndex = trigger.getTriggerIndex();

      // Annoyingly, since we hadn't set the triggerIndex yet (we can't do it before super)
      // This will would otherwise have incorporated 0 as the trigger index.
      this.windowNamespace = namespaceFor(window);
    }

    @Override
    protected StateNamespace namespaceFor(W window) {
      return StateNamespaces.windowAndTrigger(windowCoder, window, triggerIndex);
    }
  }

  private class TriggerContextImpl extends Trigger<W>.TriggerContext {

    private final ReduceFnContextFactory.StateContextImpl<W> state;
    private final ReduceFn.Timers timers;
    private final TriggerInfoImpl triggerInfo;

    private TriggerContextImpl(
        W window,
        ReduceFn.Timers timers,
        ExecutableTrigger<W> trigger,
        FinishedTriggers finishedSet) {
      trigger.getSpec().super();
      this.state = triggerState(window, trigger);
      this.timers = new TriggerTimers(window, timers);
      this.triggerInfo = new TriggerInfoImpl(trigger, finishedSet, this);
    }

    @Override
    public Trigger<W>.TriggerContext forTrigger(ExecutableTrigger<W> trigger) {
      return new TriggerContextImpl(state.window(), timers, trigger, triggerInfo.finishedSet);
    }

    @Override
    public TriggerInfo<W> trigger() {
      return triggerInfo;
    }

    @Override
    public ReduceFn.StateContext state() {
      return state;
    }

    @Override
    public W window() {
      return state.window();
    }

    @Override
    public void deleteTimer(Instant timestamp, TimeDomain domain) {
      timers.deleteTimer(timestamp, domain);
    }

    @Override
    public Instant currentProcessingTime() {
      return timers.currentProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentSynchronizedProcessingTime() {
      return timers.currentSynchronizedProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentEventTime() {
      return timers.currentEventTime();
    }
  }

  private class OnElementContextImpl extends Trigger<W>.OnElementContext {

    private final ReduceFnContextFactory.StateContextImpl<W> state;
    private final ReduceFn.Timers timers;
    private final TriggerInfoImpl triggerInfo;
    private final Instant eventTimestamp;

    private OnElementContextImpl(
        W window,
        ReduceFn.Timers timers,
        ExecutableTrigger<W> trigger,
        FinishedTriggers finishedSet,
        Instant eventTimestamp) {
      trigger.getSpec().super();
      this.state = triggerState(window, trigger);
      this.timers = new TriggerTimers(window, timers);
      this.triggerInfo = new TriggerInfoImpl(trigger, finishedSet, this);
      this.eventTimestamp = eventTimestamp;
    }


    @Override
    public Instant eventTimestamp() {
      return eventTimestamp;
    }

    @Override
    public Trigger<W>.OnElementContext forTrigger(ExecutableTrigger<W> trigger) {
      return new OnElementContextImpl(
          state.window(), timers, trigger, triggerInfo.finishedSet, eventTimestamp);
    }

    @Override
    public TriggerInfo<W> trigger() {
      return triggerInfo;
    }

    @Override
    public ReduceFn.StateContext state() {
      return state;
    }

    @Override
    public W window() {
      return state.window();
    }

    @Override
    public void setTimer(Instant timestamp, TimeDomain domain) {
      timers.setTimer(timestamp, domain);
    }


    @Override
    public void deleteTimer(Instant timestamp, TimeDomain domain) {
      timers.deleteTimer(timestamp, domain);
    }

    @Override
    public Instant currentProcessingTime() {
      return timers.currentProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentSynchronizedProcessingTime() {
      return timers.currentSynchronizedProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentEventTime() {
      return timers.currentEventTime();
    }
  }

  private class OnMergeContextImpl extends Trigger<W>.OnMergeContext {

    private final ReduceFnContextFactory.MergingStateContextImpl<W> state;
    private final ReduceFn.Timers timers;
    private final MergingTriggerInfoImpl triggerInfo;

    private OnMergeContextImpl(
        W window,
        ReduceFn.Timers timers,
        ExecutableTrigger<W> trigger,
        FinishedTriggers finishedSet,
        Collection<W> mergingWindows,
        Map<W, FinishedTriggers> finishedSets) {
      trigger.getSpec().super();
      this.state = new ReduceFnContextFactory.MergingStateContextImpl<>(
          triggerState(window, trigger), mergingWindows);
      this.timers = new TriggerTimers(window, timers);
      this.triggerInfo = new MergingTriggerInfoImpl(trigger, finishedSet, this, finishedSets);
    }

    @Override
    public Trigger<W>.OnMergeContext forTrigger(ExecutableTrigger<W> trigger) {
      return new OnMergeContextImpl(
          state.window(), timers, trigger, triggerInfo.finishedSet,
          state.mergingWindows(), triggerInfo.finishedSets);
    }

    @Override
    public Iterable<W> oldWindows() {
      return state.mergingWindows();
    }

    @Override
    public ReduceFn.MergingStateContext state() {
      return state;
    }

    @Override
    public MergingTriggerInfo<W> trigger() {
      return triggerInfo;
    }

    @Override
    public W window() {
      return state.window();
    }

    @Override
    public void setTimer(Instant timestamp, TimeDomain domain) {
      timers.setTimer(timestamp, domain);
    }

    @Override
    public void deleteTimer(Instant timestamp, TimeDomain domain) {
      timers.setTimer(timestamp, domain);

    }

    @Override
    public Instant currentProcessingTime() {
      return timers.currentProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentSynchronizedProcessingTime() {
      return timers.currentSynchronizedProcessingTime();
    }

    @Override
    @Nullable
    public Instant currentEventTime() {
      return timers.currentEventTime();
    }
  }
}
