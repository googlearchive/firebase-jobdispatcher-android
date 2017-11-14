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
import com.firebase.jobdispatcher.Constraint.JobConstraint;

/** An internal non-Job implementation of JobParameters. Passed to JobService invocations. */
/* package */ final class JobInvocation implements JobParameters {

  @NonNull private final String tag;

  @NonNull private final String service;

  @NonNull private final JobTrigger trigger;

  private final boolean recurring;

  private final int lifetime;

  @NonNull @JobConstraint private final int[] constraints;

  @NonNull private final Bundle extras;

  private final RetryStrategy retryStrategy;

  private final boolean replaceCurrent;

  private final TriggerReason triggerReason;

  private JobInvocation(Builder builder) {
    tag = builder.tag;
    service = builder.service;
    trigger = builder.trigger;
    retryStrategy = builder.retryStrategy;
    recurring = builder.recurring;
    lifetime = builder.lifetime;
    constraints = builder.constraints;
    extras = builder.extras;
    replaceCurrent = builder.replaceCurrent;
    triggerReason = builder.triggerReason;
  }

  @NonNull
  @Override
  public String getService() {
    return service;
  }

  @NonNull
  @Override
  public String getTag() {
    return tag;
  }

  @NonNull
  @Override
  public JobTrigger getTrigger() {
    return trigger;
  }

  @Override
  public int getLifetime() {
    return lifetime;
  }

  @Override
  public boolean isRecurring() {
    return recurring;
  }

  @NonNull
  @Override
  public int[] getConstraints() {
    return constraints;
  }

  @NonNull
  @Override
  public Bundle getExtras() {
    return extras;
  }

  @NonNull
  @Override
  public RetryStrategy getRetryStrategy() {
    return retryStrategy;
  }

  @Override
  public boolean shouldReplaceCurrent() {
    return replaceCurrent;
  }

  @Override
  public TriggerReason getTriggerReason() {
    return triggerReason;
  }

  static final class Builder {

    @NonNull private String tag;

    @NonNull private String service;

    @NonNull private JobTrigger trigger;

    private boolean recurring;

    private int lifetime;

    @NonNull @JobConstraint private int[] constraints;

    @NonNull private final Bundle extras = new Bundle();

    private RetryStrategy retryStrategy;

    private boolean replaceCurrent;

    private TriggerReason triggerReason;

    JobInvocation build() {
      if (tag == null || service == null || trigger == null) {
        throw new IllegalArgumentException("Required fields were not populated.");
      }
      return new JobInvocation(this);
    }

    public Builder setTag(@NonNull String mTag) {
      this.tag = mTag;
      return this;
    }

    public Builder setService(@NonNull String mService) {
      this.service = mService;
      return this;
    }

    public Builder setTrigger(@NonNull JobTrigger mTrigger) {
      this.trigger = mTrigger;
      return this;
    }

    public Builder setRecurring(boolean mRecurring) {
      this.recurring = mRecurring;
      return this;
    }

    public Builder setLifetime(@Lifetime.LifetimeConstant int mLifetime) {
      this.lifetime = mLifetime;
      return this;
    }

    public Builder setConstraints(@JobConstraint @NonNull int[] mConstraints) {
      this.constraints = mConstraints;
      return this;
    }

    public Builder addExtras(Bundle bundle) {
      if (bundle != null) {
        extras.putAll(bundle);
      }
      return this;
    }

    public Builder setRetryStrategy(RetryStrategy mRetryStrategy) {
      this.retryStrategy = mRetryStrategy;
      return this;
    }

    public Builder setReplaceCurrent(boolean mReplaceCurrent) {
      this.replaceCurrent = mReplaceCurrent;
      return this;
    }

    public Builder setTriggerReason(TriggerReason triggerReason) {
      this.triggerReason = triggerReason;
      return this;
    }
  }

  /**
   * @return true if the tag and the service of provided {@link JobInvocation} have the same values.
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || !getClass().equals(o.getClass())) {
      return false;
    }

    JobInvocation jobInvocation = (JobInvocation) o;

    return tag.equals(jobInvocation.tag) && service.equals(jobInvocation.service);
  }

  @Override
  public int hashCode() {
    int result = tag.hashCode();
    result = 31 * result + service.hashCode();
    return result;
  }
}
