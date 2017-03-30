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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.os.Bundle;
import com.firebase.jobdispatcher.Job.Builder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(constants = BuildConfig.class, manifest = Config.NONE, sdk = 23)
public class JobCoderTest {
    private final JobCoder mCoder = new JobCoder(PREFIX, true);
    private static final String PREFIX = "prefix";
    private Builder mBuilder;

    private static Builder setValidBuilderDefaults(Builder mBuilder) {
        return mBuilder
            .setTag("tag")
            .setTrigger(Trigger.NOW)
            .setService(TestJobService.class)
            .setRetryStrategy(RetryStrategy.DEFAULT_EXPONENTIAL);
    }

    @Before
    public void setUp() throws Exception {
        mBuilder = TestUtil.getBuilderWithNoopValidator();
    }

    @Test
    public void testCodingIsLossless() {
        for (JobParameters input : TestUtil.getJobCombinations(mBuilder)) {

            JobParameters output = mCoder.decode(mCoder.encode(input, input.getExtras())).build();

            TestUtil.assertJobsEqual(input, output);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEncode_throwsOnNullBundle() {
        mCoder.encode(mBuilder.build(), null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDecode_throwsOnNullBundle() {
        mCoder.decode(null);
    }

    @Test
    public void testDecode_failsWhenMissingFields() {
        assertNull("Expected null tag to cause decoding to fail",
            mCoder.decode(mCoder.encode(
                setValidBuilderDefaults(mBuilder).setTag(null).build(),
                new Bundle())));

        assertNull("Expected null service to cause decoding to fail",
            mCoder.decode(mCoder.encode(
                setValidBuilderDefaults(mBuilder).setService(null).build(),
                new Bundle())));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDecode_failsUnsupportedTrigger() {
            mCoder.decode(mCoder.encode(setValidBuilderDefaults(mBuilder).setTrigger(null).build(),
                    new Bundle()));
    }

    @Test
    public void testDecode_ignoresMissingRetryStrategy() {
        assertNotNull("Expected null retry strategy to cause decode to use a default",
            mCoder.decode(mCoder.encode(
                setValidBuilderDefaults(mBuilder).setRetryStrategy(null).build(),
                new Bundle())));
    }
}
