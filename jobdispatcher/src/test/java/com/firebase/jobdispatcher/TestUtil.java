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
import android.os.Looper;
import android.support.annotation.NonNull;

import com.firebase.jobdispatcher.Job.Builder;

import org.robolectric.internal.ShadowExtractor;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Provides common utilities helpful for testing.
 */
public class TestUtil {
    private static final String[] TAG_COMBINATIONS = {"tag", "foobar", "fooooooo", "bz", "100"};

    private static final int[] LIFETIME_COMBINATIONS = {
        Lifetime.UNTIL_NEXT_BOOT,
        Lifetime.FOREVER};

    private static final JobTrigger[] TRIGGER_COMBINATIONS = {
        Trigger.executionWindow(0, 30),
        Trigger.executionWindow(300, 600),
        Trigger.executionWindow(86400, 86400 * 2),
        Trigger.NOW
    };

    @SuppressWarnings("unchecked")
    private static final Class<MyTestJobService>[] SERVICE_COMBINATIONS =
        new Class[]{MyTestJobService.class};

    private static final RetryStrategy[] RETRY_STRATEGY_COMBINATIONS = {
        RetryStrategy.DEFAULT_LINEAR,
        new RetryStrategy(RetryStrategy.RETRY_POLICY_LINEAR, 60, 300),
        RetryStrategy.DEFAULT_EXPONENTIAL,
        new RetryStrategy(RetryStrategy.RETRY_POLICY_EXPONENTIAL, 300, 600),
    };

    public static void assertHasSinglePrivateConstructor(Class<?> cls) throws Exception {
        Constructor<?>[] constructors = cls.getDeclaredConstructors();
        assertEquals("expected number of constructors to be == 1", 1, constructors.length);

        Constructor<?> constructor = constructors[0];
        assertFalse("expected constructor to be inaccessible", constructor.isAccessible());

        constructor.setAccessible(true);
        constructor.newInstance();
    }

    static List<List<Integer>> getAllConstraintCombinations() {
        List<List<Integer>> combos = new LinkedList<>();

        combos.add(Collections.EMPTY_LIST);
        for (Integer cur : Constraint.ALL_CONSTRAINTS) {
            for (int l = combos.size() - 1; l >= 0; l--) {
                List<Integer> oldCombo = combos.get(l);
                List<Integer> newCombo = Arrays.asList(new Integer[oldCombo.size() + 1]);

                Collections.copy(newCombo, oldCombo);
                newCombo.set(oldCombo.size(), cur);
                combos.add(newCombo);
            }
            combos.add(Collections.singletonList(cur));
        }

        return combos;
    }

    static int[] toIntArray(List<Integer> list) {
        int[] input = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            input[i] = list.get(i);
        }
        return input;
    }

    static List<JobParameters> getJobCombinations(Builder builder) {
        List<JobParameters> jobs = new LinkedList<>();

        for (String tag : TAG_COMBINATIONS) {
            for (List<Integer> constraintList : getAllConstraintCombinations()) {
                for (boolean recurring : new boolean[]{true, false}) {
                    for (int lifetime : LIFETIME_COMBINATIONS) {
                        for (JobTrigger trigger : TRIGGER_COMBINATIONS) {
                            for (Class<MyTestJobService> service : SERVICE_COMBINATIONS) {
                                for (Bundle extras : getBundleCombinations()) {
                                    for (RetryStrategy rs : RETRY_STRATEGY_COMBINATIONS) {
                                        //noinspection WrongConstant
                                        jobs.add(builder
                                            .setTag(tag)
                                            .setRecurring(recurring)
                                            .setConstraints(toIntArray(constraintList))
                                            .setLifetime(lifetime)
                                            .setTrigger(trigger)
                                            .setService(service)
                                            .setExtras(extras)
                                            .setRetryStrategy(rs)
                                            .build());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return jobs;
    }

    private static Bundle[] getBundleCombinations() {
        List<Bundle> bundles = new LinkedList<>();
        bundles.add(null);
        bundles.add(new Bundle());

        Bundle b = new Bundle();
        b.putString("foo", "bar");
        b.putInt("bar", 1);
        b.putLong("baz", 3L);
        bundles.add(b);

        return bundles.toArray(new Bundle[bundles.size()]);
    }

    static void assertJobsEqual(JobParameters input, JobParameters output) {
        assertNotNull("input", input);
        assertNotNull("output", output);

        assertEquals("isRecurring()", input.isRecurring(), output.isRecurring());
        assertEquals("getLifetime()", input.getLifetime(), output.getLifetime());
        assertEquals("getTag()", input.getTag(), output.getTag());
        assertEquals("getService()", input.getService(), output.getService());
        assertEquals("getConstraints()",
            Constraint.compact(input.getConstraints()),
            Constraint.compact(output.getConstraints()));

        assertTriggersEqual(input.getTrigger(), output.getTrigger());
        assertBundlesEqual(input.getExtras(), output.getExtras());
        assertRetryStrategiesEqual(input.getRetryStrategy(), output.getRetryStrategy());

    }

    static void assertRetryStrategiesEqual(RetryStrategy in, RetryStrategy out) {
        String prefix = "getRetryStrategy().";

        assertEquals(prefix + "getPolicy()",
            in.getPolicy(), out.getPolicy());
        assertEquals(prefix + "getInitialBackoff()",
            in.getInitialBackoff(), out.getInitialBackoff());
        assertEquals(prefix + "getMaximumBackoff()",
            in.getMaximumBackoff(), out.getMaximumBackoff());
    }

    static void assertBundlesEqual(Bundle inExtras, Bundle outExtras) {
        if (inExtras == null || outExtras == null) {
            assertNull(inExtras);
            assertNull(outExtras);
            return;
        }

        assertEquals("getExtras().size()", inExtras.size(), outExtras.size());
        final Set<String> inKeys = inExtras.keySet();
        for (String key : inKeys) {
            assertTrue("getExtras().containsKey(\"" + key + "\")", outExtras.containsKey(key));
            assertEquals("getExtras().get(\"" + key + "\")", inExtras.get(key), outExtras.get(key));
        }
    }

    static void assertTriggersEqual(JobTrigger inTrigger, JobTrigger outTrigger) {
        assertEquals("", inTrigger.getClass(), outTrigger.getClass());

        if (inTrigger instanceof JobTrigger.ExecutionWindowTrigger) {
            assertEquals("getTrigger().getWindowStart()",
                ((JobTrigger.ExecutionWindowTrigger) inTrigger).getWindowStart(),
                ((JobTrigger.ExecutionWindowTrigger) outTrigger).getWindowStart());
            assertEquals("getTrigger().getWindowEnd()",
                ((JobTrigger.ExecutionWindowTrigger) inTrigger).getWindowEnd(),
                ((JobTrigger.ExecutionWindowTrigger) outTrigger).getWindowEnd());
        } else if (inTrigger == Trigger.NOW) {
            assertEquals(inTrigger, outTrigger);
        } else {
            fail("Unknown Trigger class: " + inTrigger.getClass());
        }
    }

    @NonNull
    public static Builder getBuilderWithNoopValidator() {
        return new Builder(new ValidationEnforcer(new NoopJobValidator()));
    }

    /**
     * Advances the provided Looper.
     * <p/>
     * Using {@link org.robolectric.Shadows#shadowOf(Looper)} fails due to Robolectric's unusual
     * dependency on AndroidHttpClient.
     * <p/>
     * See: https://github.com/robolectric/robolectric/issues/1862#issuecomment-159765848
     */
    static void advanceLooper(Looper looper) {
        ((ShadowLooper) ShadowExtractor.extract(looper)).getScheduler().advanceBy(100);
    }
}
