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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.firebase.jobdispatcher.FirebaseJobDispatcher.ScheduleFailedException;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for the {@link FirebaseJobDispatcher} class. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 23)
public class FirebaseJobDispatcherTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Mock private Driver driver;

  @Mock private JobValidator validator;

  private FirebaseJobDispatcher dispatcher;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);

    when(driver.getValidator()).thenReturn(validator);

    dispatcher = new FirebaseJobDispatcher(driver);
    setDriverAvailability(true);
  }

  @Test
  public void testSchedule_passThrough() throws Exception {
    final int[] possibleResults = {
      FirebaseJobDispatcher.SCHEDULE_RESULT_SUCCESS,
      FirebaseJobDispatcher.SCHEDULE_RESULT_NO_DRIVER_AVAILABLE,
      FirebaseJobDispatcher.SCHEDULE_RESULT_BAD_SERVICE,
      FirebaseJobDispatcher.SCHEDULE_RESULT_UNKNOWN_ERROR,
      FirebaseJobDispatcher.SCHEDULE_RESULT_UNSUPPORTED_TRIGGER
    };

    for (int result : possibleResults) {
      when(driver.schedule(null)).thenReturn(result);
      assertEquals(result, dispatcher.schedule(null));
    }

    verify(driver, times(possibleResults.length)).schedule(null);
  }

  @Test
  public void testSchedule_unavailable() throws Exception {
    setDriverAvailability(false);
    assertEquals(
        FirebaseJobDispatcher.SCHEDULE_RESULT_NO_DRIVER_AVAILABLE, dispatcher.schedule(null));
    verify(driver, never()).schedule(null);
  }

  @Test
  public void testCancelJob() throws Exception {
    final String tag = "foo";

    // simulate success
    when(driver.cancel(tag)).thenReturn(FirebaseJobDispatcher.CANCEL_RESULT_SUCCESS);

    assertEquals(
        "Expected dispatcher to pass the result of Driver#cancel(String, Class) through",
        FirebaseJobDispatcher.CANCEL_RESULT_SUCCESS,
        dispatcher.cancel(tag));

    // verify the driver was indeed called
    verify(driver).cancel(tag);
  }

  @Test
  public void testCancelJob_unavailable() throws Exception {
    setDriverAvailability(false); // driver is unavailable

    assertEquals(FirebaseJobDispatcher.CANCEL_RESULT_NO_DRIVER_AVAILABLE, dispatcher.cancel("foo"));

    // verify the driver was never even consulted
    verify(driver, never()).cancel("foo");
  }

  @Test
  public void testCancelAllJobs() throws Exception {
    when(driver.cancelAll()).thenReturn(FirebaseJobDispatcher.CANCEL_RESULT_SUCCESS);

    assertEquals(FirebaseJobDispatcher.CANCEL_RESULT_SUCCESS, dispatcher.cancelAll());
    verify(driver).cancelAll();
  }

  @Test
  public void testCancelAllJobs_unavailable() throws Exception {
    setDriverAvailability(false); // driver is unavailable

    assertEquals(FirebaseJobDispatcher.CANCEL_RESULT_NO_DRIVER_AVAILABLE, dispatcher.cancelAll());

    verify(driver, never()).cancelAll();
  }

  @Test
  public void testMustSchedule_success() throws Exception {
    when(driver.schedule(null)).thenReturn(FirebaseJobDispatcher.SCHEDULE_RESULT_SUCCESS);

    /* assert no exception is thrown */
    dispatcher.mustSchedule(null);
  }

  @Test
  public void testMustSchedule_unavailable() throws Exception {
    setDriverAvailability(false); // driver is unavailable
    expectedException.expect(FirebaseJobDispatcher.ScheduleFailedException.class);

    dispatcher.mustSchedule(null);
  }

  @Test
  public void testMustSchedule_failure() throws Exception {
    final int[] possibleErrors = {
      FirebaseJobDispatcher.SCHEDULE_RESULT_NO_DRIVER_AVAILABLE,
      FirebaseJobDispatcher.SCHEDULE_RESULT_BAD_SERVICE,
      FirebaseJobDispatcher.SCHEDULE_RESULT_UNKNOWN_ERROR,
      FirebaseJobDispatcher.SCHEDULE_RESULT_UNSUPPORTED_TRIGGER
    };

    for (int scheduleError : possibleErrors) {
      when(driver.schedule(null)).thenReturn(scheduleError);

      try {
        dispatcher.mustSchedule(null);

        fail("Expected mustSchedule() with error code " + scheduleError + " to fail");
      } catch (ScheduleFailedException expected) {
        /* expected */
      }
    }

    verify(driver, times(possibleErrors.length)).schedule(null);
  }

  @Test
  public void testNewRetryStrategyBuilder() {
    // custom validator that only approves strategies where initialbackoff == 30s
    when(validator.validate(any(RetryStrategy.class)))
        .thenAnswer(
            new Answer<List<String>>() {
              @Override
              public List<String> answer(InvocationOnMock invocation) throws Throwable {
                RetryStrategy rs = (RetryStrategy) invocation.getArguments()[0];
                // only succeed if initialBackoff == 30s
                return rs.getInitialBackoff() == 30 ? null : Arrays.asList("foo", "bar");
              }
            });

    try {
      dispatcher.newRetryStrategy(RetryStrategy.RETRY_POLICY_EXPONENTIAL, 0, 30);
      fail("Expected initial backoff != 30s to fail using custom validator");
    } catch (Exception unused) {
      /* unused */
    }

    try {
      dispatcher.newRetryStrategy(RetryStrategy.RETRY_POLICY_EXPONENTIAL, 30, 30);
    } catch (Exception e) {
      throw new AssertionError(
          "Expected initial backoff == 30s not to fail using custom validator", e);
    }
  }

  public void setDriverAvailability(boolean driverAvailability) {
    when(driver.isAvailable()).thenReturn(driverAvailability);
  }
}
