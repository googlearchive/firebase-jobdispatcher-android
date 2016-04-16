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
 * An internal non-Job implementation of JobParameters. Passed to JobService invocations.
 */
/* package */ final class JobInvocation implements JobParameters {

    @NonNull
    private final String mTag;

    @NonNull
    private final String mService;

    @NonNull
    private final JobTrigger mTrigger;

    private final boolean mRecurring;

    private final int mLifetime;

    @NonNull
    @JobConstraint
    private final int[] mConstraints;

    @NonNull
    private final Bundle mExtras;

    private final RetryStrategy mRetryStrategy;

    private final boolean mReplaceCurrent;

    JobInvocation(@NonNull String tag, @NonNull String service, @NonNull JobTrigger trigger,
                  @NonNull RetryStrategy retryStrategy, boolean recurring,
                  @Lifetime.LifetimeConstant int lifetime,
                  @NonNull @JobConstraint int[] constraints, @Nullable Bundle extras,
                  boolean replaceCurrent) {

        mTag = tag;
        mService = service;
        mTrigger = trigger;
        mRetryStrategy = retryStrategy;
        mRecurring = recurring;
        mLifetime = lifetime;
        mConstraints = constraints;
        mExtras = extras != null ? extras : new Bundle();
        mReplaceCurrent = replaceCurrent;
    }

    @NonNull
    @Override
    public String getService() {
        return mService;
    }

    @NonNull
    @Override
    public String getTag() {
        return mTag;
    }

    @NonNull
    @Override
    public JobTrigger getTrigger() {
        return mTrigger;
    }

    @Override
    public int getLifetime() {
        return mLifetime;
    }

    @Override
    public boolean isRecurring() {
        return mRecurring;
    }

    @NonNull
    @Override
    public int[] getConstraints() {
        return mConstraints;
    }

    @NonNull
    @Override
    public Bundle getExtras() {
        return mExtras;
    }

    @NonNull
    @Override
    public RetryStrategy getRetryStrategy() {
        return mRetryStrategy;
    }

    @Override
    public boolean shouldReplaceCurrent() {
        return mReplaceCurrent;
    }
}
