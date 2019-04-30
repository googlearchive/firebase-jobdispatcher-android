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

import static com.firebase.jobdispatcher.GooglePlayReceiver.getJobCoder;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.annotation.AnyThread;
import android.support.annotation.BinderThread;
import android.support.annotation.IntDef;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.annotation.WorkerThread;
import android.support.v4.util.SimpleArrayMap;
import android.text.format.DateUtils;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import org.json.JSONObject;

/**
 * JobService is the fundamental unit of work used in the JobDispatcher.
 *
 * <p>Users will need to override {@link #onStartJob(JobParameters)}, which is where any
 * asynchronous execution should start. This method, like most lifecycle methods, runs on the main
 * thread; you <b>must</b> offload execution to another thread (or {@link android.os.AsyncTask}, or
 * {@link android.os.Handler}, or your favorite flavor of concurrency).
 *
 * <p>Once any asynchronous work is complete {@link #jobFinished(JobParameters, boolean)} should be
 * called to inform the backing driver of the result.
 *
 * <p>Implementations should also override {@link #onStopJob(JobParameters)}, which will be called
 * if the scheduling engine wishes to interrupt your work (most likely because the runtime
 * constraints that are associated with the job in question are no longer met).
 *
 * @deprecated Firebase Job Dispatcher is deprecated. Apps should migrate to WorkManager before Apr
 *     7, 2020. Please see FJD's README.md file for more information.
 */
@Deprecated
public abstract class JobService extends Service {

  /**
   * Returned to indicate the job was executed successfully. If the job is not recurring (i.e. a
   * one-off) it will be dequeued and forgotten. If it is recurring the trigger will be reset and
   * the job will be requeued.
   */
  public static final int RESULT_SUCCESS = 0;

  /**
   * Returned to indicate the job encountered an error during execution and should be retried after
   * a backoff period.
   */
  public static final int RESULT_FAIL_RETRY = 1;

  /**
   * Returned to indicate the job encountered an error during execution but should not be retried.
   * If the job is not recurring (i.e. a one-off) it will be dequeued and forgotten. If it is
   * recurring the trigger will be reset and the job will be requeued.
   */
  public static final int RESULT_FAIL_NORETRY = 2;

  /** The result returned from a job execution. */
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({RESULT_SUCCESS, RESULT_FAIL_RETRY, RESULT_FAIL_NORETRY})
  public @interface JobResult {}

  static final String TAG = "FJD.JobService";

  @VisibleForTesting
  static final String ACTION_EXECUTE = "com.firebase.jobdispatcher.ACTION_EXECUTE";

  private static final Handler mainHandler = new Handler(Looper.getMainLooper());

  /** A background executor that lazily creates up to one thread. */
  @VisibleForTesting
  final ExecutorService backgroundExecutor =
      new ThreadPoolExecutor(
          /* corePoolSize= */ 0,
          /* maximumPoolSize= */ 1,
          /* keepAliveTime= */ 60L,
          /* unit= */ SECONDS,
          /* workQueue= */ new LinkedBlockingQueue<Runnable>());

  /**
   * Correlates job tags (unique strings) with Messages, which are used to signal the completion of
   * a job.
   *
   * <p>All access should happen on the {@link #backgroundExecutor}.
   */
  // @GuardedBy("runningJobs")
  private final SimpleArrayMap<String, JobCallback> runningJobs = new SimpleArrayMap<>(1);

  private final IRemoteJobService.Stub binder =
      new IRemoteJobService.Stub() {
        @Override
        @BinderThread
        public void start(Bundle invocationData, IJobCallback callback) {
          JobInvocation.Builder invocation = getJobCoder().decode(invocationData);
          if (invocation == null) {
            Log.wtf(TAG, "start: unknown invocation provided");
            return;
          }

          JobService.this.handleStartJobRequest(invocation.build(), callback);
        }

        @Override
        @BinderThread
        public void stop(Bundle invocationData, boolean needToSendResult) {
          JobInvocation.Builder invocation = getJobCoder().decode(invocationData);
          if (invocation == null) {
            Log.wtf(TAG, "stop: unknown invocation provided");
            return;
          }

          JobService.this.handleStopJobRequest(invocation.build(), needToSendResult);
        }
      };

  /**
   * The entry point to your Job. Implementations should offload work to another thread of execution
   * as soon as possible because this runs on the main thread. If work was offloaded, call {@link
   * JobService#jobFinished(JobParameters, boolean)} to notify the scheduling service that the work
   * is completed.
   *
   * <p>If a job with the same service and tag was rescheduled during execution {@link
   * JobService#onStopJob(JobParameters)} will be called and the wakelock will be released. Please
   * make sure that all reschedule requests happen at the end of the job.
   *
   * @return {@code true} if there is more work remaining in the worker thread, {@code false} if the
   *     job was completed.
   */
  @MainThread
  public abstract boolean onStartJob(@NonNull JobParameters job);

  /**
   * Called when the scheduling engine has decided to interrupt the execution of a running job, most
   * likely because the runtime constraints associated with the job are no longer satisfied. The job
   * must stop execution.
   *
   * @return true if the job should be retried
   * @see com.firebase.jobdispatcher.JobInvocation.Builder#setRetryStrategy(RetryStrategy)
   * @see RetryStrategy
   */
  @MainThread
  public abstract boolean onStopJob(@NonNull JobParameters job);

  /**
   * Asks the {@code job} to start running. Calls {@link #onStartJob} on the main thread. Once
   * complete, the {@code callback} will be used to send the result back.
   */
  @BinderThread
  private void handleStartJobRequest(JobParameters job, IJobCallback callback) {
    backgroundExecutor.execute(UnitOfWork.handleStartJobRequest(this, job, callback));
  }

  /**
   * Records that the provided {@code job} has been started, then arranges for {@link
   * #onStartJob(JobParameters)} to be called on the main thread (via {@link
   * #callOnStartJobImpl(JobParameters)}.
   */
  @WorkerThread
  private void handleStartJobRequestImpl(final JobParameters job, IJobCallback callback) {
    synchronized (runningJobs) {
      if (runningJobs.containsKey(job.getTag())) {
        Log.w(
            TAG, String.format(Locale.US, "Job with tag = %s was already running.", job.getTag()));
        return;
      }
      runningJobs.put(job.getTag(), new JobCallback(job, callback, SystemClock.elapsedRealtime()));
    }

    // onStartJob needs to be called on the main thread
    mainHandler.post(UnitOfWork.callOnStartJob(this, job));
  }

  /** Calls {@link #onStartJob(JobParameters)}. Should only be run on the main thread. */
  @MainThread
  private void callOnStartJobImpl(JobParameters jobParameters) {
    boolean moreWork = onStartJob(jobParameters);

    if (!moreWork) {
      // If there's no more work to do, we're done. Report success.
      backgroundExecutor.execute(
          UnitOfWork.removeAndFinishJobWithResult(
              this, jobParameters, /* result= */ RESULT_SUCCESS));
    }
  }

  /**
   * Asks job to stop.
   *
   * <p>Sending results can be skipped if the call was initiated by a reschedule request.
   */
  @BinderThread
  private void handleStopJobRequest(JobParameters job, boolean needToSendResult) {
    backgroundExecutor.execute(
        UnitOfWork.handleStopJobRequest(this, job, /* needToSendResult= */ needToSendResult));
  }

  @WorkerThread
  private void handleStopJobRequestImpl(final JobParameters job, final boolean needToSendResult) {
    synchronized (runningJobs) {
      JobCallback jobCallback = runningJobs.remove(job.getTag());
      if (jobCallback == null) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
          Log.d(TAG, "Provided job has already been executed.");
        }
        return;
      }

      // onStopJob needs to be called on the main thread
      mainHandler.post(
          UnitOfWork.callOnStopJob(
              this,
              jobCallback,
              /* needToSendResult= */ needToSendResult,
              /* terminatingResult= */ RESULT_SUCCESS));
    }
  }

  /** Calls {@link #onStopJob(JobParameters)}. Should only be run on the main thread. */
  @MainThread
  private void callOnStopJobImpl(
      JobCallback jobCallback, boolean needToSendResult, @JobResult int terminatingResult) {
    boolean shouldRetry = onStopJob(jobCallback.job);
    if (needToSendResult) {
      backgroundExecutor.execute(
          UnitOfWork.finishJobWithResult(
              jobCallback, shouldRetry ? RESULT_FAIL_RETRY : terminatingResult));
    }
  }

  /**
   * Callback to inform the scheduling driver that you've finished executing. Can be called from any
   * thread. When the system receives this message, it will release the wakelock being held.
   *
   * @param job
   * @param needsReschedule whether the job should be rescheduled
   * @see com.firebase.jobdispatcher.JobInvocation.Builder#setRetryStrategy(RetryStrategy)
   */
  @AnyThread
  public final void jobFinished(@NonNull JobParameters job, boolean needsReschedule) {
    if (job == null) {
      Log.e(TAG, "jobFinished called with a null JobParameters");
      return;
    }

    this.backgroundExecutor.execute(
        UnitOfWork.removeAndFinishJobWithResult(
            this, job, /* result= */ needsReschedule ? RESULT_FAIL_RETRY : RESULT_SUCCESS));
  }

  /**
   * Removes the provided {@code job} from the list of {@link #runningJobs} and sends the {@code
   * result} if the job wasn't already complete.
   */
  @WorkerThread
  private void removeAndFinishJobWithResultImpl(JobParameters job, @JobResult int result) {
    synchronized (runningJobs) {
      JobCallback callback = runningJobs.remove(job.getTag());
      if (callback != null) {
        callback.sendResult(result);
      }
    }
  }

  @Override
  @MainThread
  public final int onStartCommand(Intent intent, int flags, int startId) {
    stopSelf(startId);

    return START_NOT_STICKY;
  }

  @Nullable
  @Override
  @MainThread
  public final IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  @MainThread
  public final boolean onUnbind(Intent intent) {
    backgroundExecutor.execute(UnitOfWork.handleOnUnbindEvent(this, intent));
    return super.onUnbind(intent);
  }

  @WorkerThread
  private void handleOnUnbindEventImpl(Intent unusedIntent) {
    synchronized (runningJobs) {
      for (int i = runningJobs.size() - 1; i >= 0; i--) {
        JobCallback callback = runningJobs.remove(runningJobs.keyAt(i));
        if (callback != null) {
          // Ask the job to stop. onStopJob needs to be called on the main thread
          mainHandler.post(
              UnitOfWork.callOnStopJob(
                  this,
                  callback,
                  /* needToSendResult= */ true,
                  /* terminatingResult= */ RESULT_FAIL_NORETRY));
        }
      }
    }
  }

  @Override
  @MainThread
  public final void onRebind(Intent intent) {
    super.onRebind(intent);
  }

  @Override
  @MainThread
  public final void onStart(Intent intent, int startId) {}

  /**
   * Package-private alias for {@link #dump(FileDescriptor, PrintWriter, String[])}.
   *
   * <p>The {@link #dump(FileDescriptor, PrintWriter, String[])} method is protected. This
   * implementation method is marked package-private to facilitate testing.
   */
  @VisibleForTesting
  final void dumpImpl(PrintWriter writer) {
    synchronized (runningJobs) {
      if (runningJobs.isEmpty()) {
        writer.println("No running jobs");
        return;
      }

      long now = SystemClock.elapsedRealtime();

      writer.println("Running jobs:");
      for (int i = 0; i < runningJobs.size(); i++) {
        JobCallback callback = runningJobs.get(runningJobs.keyAt(i));

        // Add sanitized quotes around the tag to make this easier to parse for robots
        String name = JSONObject.quote(callback.job.getTag());
        // Produces strings like "02:30"
        String duration =
            DateUtils.formatElapsedTime(MILLISECONDS.toSeconds(now - callback.startedAtElapsed));

        writer.println("    * " + name + " has been running for " + duration);
      }
    }
  }

  @Override
  protected final void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
    dumpImpl(writer);
  }

  @Override
  @MainThread
  public final void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
  }

  @Override
  @MainThread
  public final void onTaskRemoved(Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
  }

  private static final class JobCallback {
    final JobParameters job;
    final IJobCallback remoteCallback;
    final long startedAtElapsed;

    private JobCallback(JobParameters job, IJobCallback callback, long startedAtElapsed) {
      this.job = job;
      this.remoteCallback = callback;
      this.startedAtElapsed = startedAtElapsed;
    }

    void sendResult(@JobResult int result) {
      try {
        remoteCallback.jobFinished(getJobCoder().encode(job, new Bundle()), result);
      } catch (RemoteException remoteException) {
        Log.e(TAG, "Failed to send result to driver", remoteException);
      }
    }
  }

  /**
   * A runnable that calls various JobService methods.
   *
   * <p>Instances should be constructed via the static factory methods. Kept as a single class to
   * reduce impact on APK size.
   */
  private static class UnitOfWork implements Runnable {

    /** See {@link #callOnStartJob(JobService, JobParameters). */
    private static final int CALL_ON_START_JOB = 1;

    /** See {@link #callOnStopJob(JobService, JobCallback, boolean, int}). */
    private static final int CALL_ON_STOP_JOB = 2;

    /** See {@link #handleOnUnbindEvent(JobService, Intent)}. */
    private static final int HANDLE_ON_UNBIND_EVENT = 3;

    /** See {@link #handleStartJobRequest(JobService, JobParameters, IJobCallback)}. */
    private static final int HANDLE_START_JOB_REQUEST = 4;

    /** See {@link #handleStopJobRequest(JobService, JobParameters, boolean)}. */
    private static final int HANDLE_STOP_JOB_REQUEST = 5;

    /** See {@link #finishJobWithResult(JobCallback, int)}. */
    private static final int FINISH_JOB_WITH_RESULT = 6;

    /** See {@link #removeAndFinishJobWithResult(JobService, JobParameters, int)}. */
    private static final int REMOVE_AND_FINISH_JOB_WITH_RESULT = 7;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
      CALL_ON_START_JOB,
      CALL_ON_STOP_JOB,
      HANDLE_ON_UNBIND_EVENT,
      HANDLE_START_JOB_REQUEST,
      HANDLE_STOP_JOB_REQUEST,
      FINISH_JOB_WITH_RESULT,
      REMOVE_AND_FINISH_JOB_WITH_RESULT,
    })
    private @interface WorkType {}

    /** The type of work to do. Always set. */
    @WorkType private final int workType;

    /** The JobService to do the work on. Always set. */
    @NonNull private final JobService jobService;

    /**
     * Set for {@link #CALL_ON_START_JOB}, {@link #CALL_ON_STOP_JOB}, {@link
     * #HANDLE_START_JOB_REQUEST}, {@link #HANDLE_STOP_JOB_REQUEST}, and {@link
     * #REMOVE_AND_FINISH_JOB_WITH_RESULT}.
     */
    @Nullable private final JobParameters jobParameters;

    /** Set for {@link #HANDLE_START_JOB_REQUEST}. */
    @Nullable private final IJobCallback remoteJobCallback;

    /** Set for {@link #CALL_ON_STOP_JOB} and {@link #FINISH_JOB_WITH_RESULT}. */
    @Nullable private final JobCallback jobCallback;

    /**
     * Set for {@link #CALL_ON_STOP_JOB}, {@link #FINISH_JOB_WITH_RESULT}, and {@link
     * #REMOVE_AND_FINISH_JOB_WITH_RESULT}.
     */
    @JobResult private final int terminatingResult;

    /**
     * Boolean value whose meaning changes depending on the {@link #workType}.
     *
     * <p>Set for {@link #HANDLE_STOP_JOB_REQUEST} and {@link #CALL_ON_STOP_JOB}.
     */
    private final boolean boolValue;

    /** Set for {@link #HANDLE_ON_UNBIND_EVENT}. */
    @Nullable private final Intent unbindIntent;

    private UnitOfWork(
        @WorkType int workType,
        @NonNull JobService jobService,
        @Nullable JobParameters jobParameters,
        @Nullable IJobCallback remoteJobCallback,
        @Nullable JobCallback jobCallback,
        @Nullable Intent unbindIntent,
        boolean boolValue,
        @JobResult int terminatingResult) {
      this.workType = workType;
      this.jobService = jobService;
      this.jobParameters = jobParameters;
      this.remoteJobCallback = remoteJobCallback;
      this.jobCallback = jobCallback;
      this.unbindIntent = unbindIntent;
      this.boolValue = boolValue;
      this.terminatingResult = terminatingResult;
    }

    /** Creats a Runnable that calls {@link JobService#callOnStartJobImpl(JobParameters)}. */
    static UnitOfWork callOnStartJob(JobService jobService, JobParameters jobParameters) {
      return new UnitOfWork(
          CALL_ON_START_JOB,
          /* jobService= */ jobService,
          /* jobParameters= */ jobParameters,
          /* remoteJobCallback= */ null,
          /* jobCallback= */ null,
          /* unbindIntent= */ null,
          /* boolValue= */ false,
          /* terminatingResult= */ RESULT_SUCCESS);
    }

    /**
     * Creats a Runnable that calls {@link JobService#callOnStopJobImpl(JobParameters, JobCallback,
     * boolean, int)}.
     */
    static UnitOfWork callOnStopJob(
        JobService jobService,
        JobCallback jobCallback,
        boolean needToSendResult,
        @JobResult int terminatingResult) {
      return new UnitOfWork(
          CALL_ON_STOP_JOB,
          /* jobService= */ jobService,
          /* jobParameters= */ null,
          /* remoteJobCallback= */ null,
          /* jobCallback= */ jobCallback,
          /* unbindIntent= */ null,
          /* boolValue= */ needToSendResult,
          /* terminatingResult= */ terminatingResult);
    }

    /** Creats a Runnable that calls {@link JobService#handleOnUnbindEventImpl(Intent)}. */
    static UnitOfWork handleOnUnbindEvent(
        @NonNull JobService jobService, @NonNull Intent unbindIntent) {
      return new UnitOfWork(
          HANDLE_ON_UNBIND_EVENT,
          jobService,
          /* jobParameters= */ null,
          /* remoteJobCallback= */ null,
          /* jobCallback= */ null,
          /* unbindIntent= */ unbindIntent,
          /* boolValue= */ false,
          /* terminatingResult= */ RESULT_SUCCESS);
    }

    /**
     * Creats a Runnable that calls {@link JobService#handleStartJobRequestImpl(JobParameters,
     * IJobCallback)}.
     */
    static UnitOfWork handleStartJobRequest(
        @NonNull JobService jobService,
        @NonNull JobParameters jobParameters,
        @NonNull IJobCallback remoteJobCallback) {
      return new UnitOfWork(
          HANDLE_START_JOB_REQUEST,
          jobService,
          /* jobParameters= */ jobParameters,
          /* remoteJobCallback= */ remoteJobCallback,
          /* jobCallback= */ null,
          /* unbindIntent= */ null,
          /* boolValue= */ false,
          /* terminatingResult= */ RESULT_SUCCESS);
    }

    /**
     * Creats a Runnable that calls {@link JobService#handleStopJobRequestImpl(JobParameters,
     * boolean)}.
     */
    static UnitOfWork handleStopJobRequest(
        @NonNull JobService jobService,
        @NonNull JobParameters jobParameters,
        boolean needToSendResult) {
      return new UnitOfWork(
          HANDLE_STOP_JOB_REQUEST,
          jobService,
          /* jobParameters= */ jobParameters,
          /* remoteJobCallback= */ null,
          /* jobCallback= */ null,
          /* unbindIntent= */ null,
          /* boolValue= */ needToSendResult,
          /* terminatingResult= */ RESULT_SUCCESS);
    }

    /** Creats a Runnable that calls {@link TODO} */
    static UnitOfWork finishJobWithResult(@NonNull JobCallback jobCallback, @JobResult int result) {

      return new UnitOfWork(
          FINISH_JOB_WITH_RESULT,
          /* jobService= */ null,
          /* jobParameters= */ null,
          /* remoteJobCallback= */ null,
          /* jobCallback= */ jobCallback,
          /* unbindIntent= */ null,
          /* boolValue= */ false,
          /* terminatingResult= */ result);
    }

    /**
     * Creats a Runnable that calls {@link
     * JobService#removeAndFinishJobWithResultImpl(JobParameters, int)}.
     */
    static UnitOfWork removeAndFinishJobWithResult(
        @NonNull JobService jobService,
        @NonNull JobParameters jobParameters,
        @JobResult int result) {
      return new UnitOfWork(
          REMOVE_AND_FINISH_JOB_WITH_RESULT,
          jobService,
          /* jobParameters= */ jobParameters,
          /* remoteJobCallback= */ null,
          /* jobCallback= */ null,
          /* unbindIntent= */ null,
          /* boolValue= */ false,
          /* terminatingResult= */ result);
    }

    @Override
    public void run() {
      switch (workType) {
        case CALL_ON_START_JOB: // called on main thread
          jobService.callOnStartJobImpl(jobParameters);
          return;

        case CALL_ON_STOP_JOB: // called on main thread
          jobService.callOnStopJobImpl(
              jobCallback, /* needToSendResult= */ boolValue, terminatingResult);
          return;

        case HANDLE_ON_UNBIND_EVENT:
          jobService.handleOnUnbindEventImpl(unbindIntent);
          return;

        case HANDLE_START_JOB_REQUEST:
          jobService.handleStartJobRequestImpl(jobParameters, remoteJobCallback);
          return;

        case HANDLE_STOP_JOB_REQUEST:
          jobService.handleStopJobRequestImpl(jobParameters, /* needToSendResult= */ boolValue);
          return;

        case FINISH_JOB_WITH_RESULT:
          jobCallback.sendResult(terminatingResult);
          return;

        case REMOVE_AND_FINISH_JOB_WITH_RESULT:
          jobService.removeAndFinishJobWithResultImpl(
              jobParameters, /* result= */ terminatingResult);
          return;

        default:
          throw new AssertionError("unreachable");
      }
    }
  }
}
