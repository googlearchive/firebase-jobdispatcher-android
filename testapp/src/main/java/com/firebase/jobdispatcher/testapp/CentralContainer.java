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

package com.firebase.jobdispatcher.testapp;

import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Log;
import com.firebase.jobdispatcher.Driver;
import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.firebase.jobdispatcher.Job;
import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.JobValidator;
import java.util.Iterator;

/** A singleton dependency container. */
public final class CentralContainer {
  private static JobStore sStore;

  private static boolean sInitialized = false;
  private static final Object sLock = new Object();
  private static FirebaseJobDispatcher sDispatcher;

  public static void init(Context ctx) {
    if (!sInitialized) {
      synchronized (sLock) {
        if (!sInitialized) {
          initLocked(ctx);
        }
      }
    }
  }

  private static void initLocked(Context ctx) {
    sStore = new JobStore();
    sDispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(ctx));

    sInitialized = true;
  }

  public static JobStore getStore(Context ctx) {
    init(ctx);

    return sStore;
  }

  public static FirebaseJobDispatcher getDispatcher(Context ctx) {
    init(ctx);

    return sDispatcher;
  }

  private static class TrackingBackend implements Driver {
    private final Driver mDriver;
    private final JobStore mStore;

    public TrackingBackend(Driver backend, JobStore store) {
      mDriver = backend;
      mStore = store;
    }

    @Override
    public int schedule(@NonNull Job job) {
      Log.i("TrackingBackend", "beginning schedule loop");

      synchronized (mStore) {
        final Iterator<JobHistory> it = mStore.iterator();
        while (it.hasNext()) {
          JobParameters j = it.next().job;

          if (j.getTag().equals(job.getTag()) && j.getService().equals(job.getService())) {
            it.remove();
          }
        }

        mStore.add(new JobHistory(job));
      }

      Log.i("TrackingBackend", "ending schedule loop");

      return mDriver.schedule(job);
    }

    @Override
    public int cancel(@NonNull String tag) {
      synchronized (mStore) {
        final Iterator<JobHistory> it = mStore.iterator();
        while (it.hasNext()) {
          JobParameters j = it.next().job;
          if (tag == null || tag.equals(j.getTag())) {
            it.remove();
            break;
          }
        }

        return mDriver.cancel(tag);
      }
    }

    @Override
    public int cancelAll() {
      return mDriver.cancelAll();
    }

    @NonNull
    @Override
    public JobValidator getValidator() {
      return mDriver.getValidator();
    }

    @Override
    public boolean isAvailable() {
      return mDriver.isAvailable();
    }
  }
}
