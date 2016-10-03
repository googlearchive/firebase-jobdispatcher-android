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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.support.annotation.NonNull;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricGradleTestRunner.class)
@Config(constants = BuildConfig.class, manifest = Config.NONE, sdk = 21)
public class JobInvocationTest {

    @SuppressWarnings("ConstantConditions")
    @Test
    public void testShouldReplaceCurrent() throws Exception {
        assertTrue("Expected shouldReplaceCurrent() to return value passed in constructor",
            createJobInvocation(true).shouldReplaceCurrent());
        assertFalse("Expected shouldReplaceCurrent() to return value passed in constructor",
            createJobInvocation(false).shouldReplaceCurrent());
    }

    @NonNull
    private JobInvocation createJobInvocation(boolean replaceCurrent) {
        return new JobInvocation(
            null,
            null,
            null,
            null,
            false,
            Lifetime.FOREVER,
            null,
            null,
            replaceCurrent);
    }
}
