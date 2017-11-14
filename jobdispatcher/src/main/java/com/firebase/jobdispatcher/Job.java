// Copyright 2016 Google, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.firebase.jobdispatcher;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import com.firebase.jobdispatcher.Constraint.JobConstraint;

/**
 * Job is the embodiment of a unit of work and an associated set of triggers, settings, and runtime
 * constraints.
 */
public final class Job implements JobParameters {
  private final String service;
  private final String tag;
  private final JobTrigger trigger;
  private final RetryStrategy retryStrategy;
  private final int lifetime;
  private final boolean recurring;
  private final int[] constraints;
  private final boolean replaceCurrent;
  private final Bundle extras;

  private Job(Builder builder) {
    service = builder.serviceClassName;
    extras = builder.extras == null ? null : new Bundle(builder.extras); // Make a copy
    tag = builder.tag;
    trigger = builder.trigger;
    retryStrategy = builder.retryStrategy;
    lifetime = builder.lifetime;
    recurring = builder.recurring;
    constraints = builder.constraints != null ? builder.constraints : new int[0];
    replaceCurrent = builder.replaceCurrent;
  }

  /** {@inheritDoc} */
  @NonNull
  @Override
  public int[] getConstraints() {
    return constraints;
  }

  /** {@inheritDoc} */
  @Nullable
  @Override
  public Bundle getExtras() {
    return extras;
  }

  /** {@inheritDoc} */
  @NonNull
  @Override
  public RetryStrategy getRetryStrategy() {
    return retryStrategy;
  }

  /** {@inheritDoc} */
  @Override
  public boolean shouldReplaceCurrent() {
    return replaceCurrent;
  }

  @Nullable
  @Override
  public TriggerReason getTriggerReason() {
    return null;
  }

  /** {@inheritDoc} */
  @NonNull
  @Override
  public String getTag() {
    return tag;
  }

  /** {@inheritDoc} */
  @NonNull
  @Override
  public JobTrigger getTrigger() {
    return trigger;
  }

  /** {@inheritDoc} */
  @Override
  public int getLifetime() {
    return lifetime;
  }

  /** {@inheritDoc} */
  @Override
  public boolean isRecurring() {
    return recurring;
  }

  /** {@inheritDoc} */
  @NonNull
  @Override
  public String getService() {
    return service;
  }

  /**
   * A class that understands how to build a {@link Job}. Retrieved by calling {@link
   * FirebaseJobDispatcher#newJobBuilder()}.
   */
  public static final class Builder implements JobParameters {
    private final ValidationEnforcer validator;

    private String serviceClassName;
    private Bundle extras;
    private String tag;
    private JobTrigger trigger = Trigger.NOW;
    private int lifetime = Lifetime.UNTIL_NEXT_BOOT;
    private int[] constraints;

    private RetryStrategy retryStrategy = RetryStrategy.DEFAULT_EXPONENTIAL;
    private boolean replaceCurrent = false;
    private boolean recurring = false;

    Builder(ValidationEnforcer validator) {
      this.validator = validator;
    }

    Builder(ValidationEnforcer validator, JobParameters job) {
      this.validator = validator;

      tag = job.getTag();
      serviceClassName = job.getService();
      trigger = job.getTrigger();
      recurring = job.isRecurring();
      lifetime = job.getLifetime();
      constraints = job.getConstraints();
      extras = job.getExtras();
      retryStrategy = job.getRetryStrategy();
    }

    /** Adds the provided constraint to the current list of runtime constraints. */
    public Builder addConstraint(@JobConstraint int constraint) {
      // Create a new, longer constraints array
      int[] newConstraints = new int[constraints == null ? 1 : constraints.length + 1];

      if (constraints != null && constraints.length != 0) {
        // Copy all the old values over
        System.arraycopy(constraints, 0, newConstraints, 0, constraints.length);
      }

      // add the new value
      newConstraints[newConstraints.length - 1] = constraint;
      // update the pointer
      constraints = newConstraints;

      return this;
    }

    /** Sets whether this Job should replace pre-existing Jobs with the same tag. */
    public Builder setReplaceCurrent(boolean replaceCurrent) {
      this.replaceCurrent = replaceCurrent;

      return this;
    }

    /**
     * Builds the Job, using the settings provided so far.
     *
     * @throws ValidationEnforcer.ValidationException
     */
    public Job build() {
      validator.ensureValid(this);

      return new Job(this);
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public String getService() {
      return serviceClassName;
    }

    /** Sets the backing JobService class for the Job. See {@link #getService()}. */
    public Builder setService(Class<? extends JobService> serviceClass) {
      serviceClassName = serviceClass == null ? null : serviceClass.getName();

      return this;
    }

    /**
     * Sets the backing JobService class name for the Job. See {@link #getService()}.
     *
     * <p>Should not be exposed, for internal use only.
     */
    Builder setServiceName(String serviceClassName) {
      this.serviceClassName = serviceClassName;

      return this;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public String getTag() {
      return tag;
    }

    /** Sets the unique String tag used to identify the Job. See {@link #getTag()}. */
    public Builder setTag(String tag) {
      this.tag = tag;

      return this;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public JobTrigger getTrigger() {
      return trigger;
    }

    /** Sets the Trigger used for the Job. See {@link #getTrigger()}. */
    public Builder setTrigger(JobTrigger trigger) {
      this.trigger = trigger;

      return this;
    }

    /** {@inheritDoc} */
    @Override
    @Lifetime.LifetimeConstant
    public int getLifetime() {
      return lifetime;
    }

    /** Sets the Job's lifetime, or how long it should persist. See {@link #getLifetime()}. */
    public Builder setLifetime(@Lifetime.LifetimeConstant int lifetime) {
      this.lifetime = lifetime;

      return this;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isRecurring() {
      return recurring;
    }

    /** Sets whether the job should recur. The default is false. */
    public Builder setRecurring(boolean recurring) {
      this.recurring = recurring;

      return this;
    }

    /** {@inheritDoc} */
    @Override
    @JobConstraint
    public int[] getConstraints() {
      return constraints == null ? new int[] {} : constraints;
    }

    /** Sets the Job's runtime constraints. See {@link #getConstraints()}. */
    public Builder setConstraints(@JobConstraint int... constraints) {
      this.constraints = constraints;

      return this;
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public Bundle getExtras() {
      return extras;
    }

    /** Sets the user-defined extras associated with the Job. See {@link #getExtras()}. */
    public Builder setExtras(Bundle extras) {
      this.extras = extras;

      return this;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public RetryStrategy getRetryStrategy() {
      return retryStrategy;
    }

    /** Set the RetryStrategy used for the Job. See {@link #getRetryStrategy()}. */
    public Builder setRetryStrategy(RetryStrategy retryStrategy) {
      this.retryStrategy = retryStrategy;

      return this;
    }

    /** {@inheritDoc} */
    @Override
    public boolean shouldReplaceCurrent() {
      return replaceCurrent;
    }

    @Nullable
    @Override
    public TriggerReason getTriggerReason() {
      return null;
    }
  }
}
