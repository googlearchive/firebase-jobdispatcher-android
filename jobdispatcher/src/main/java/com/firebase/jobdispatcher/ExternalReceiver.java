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

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.support.v4.util.SimpleArrayMap;
import android.util.Log;
import com.firebase.jobdispatcher.JobService.JobResult;

/**
 * An ExternalReceiver is something that understands how to convert a request from an external
 * entity (like the GCM Network Manager) into one that {@link JobService} understands.
 * <p/>
 * Subclasses should override {@link #onJobFinished(JobParameters, int)}, as well as
 * {@link #onStartCommand(Intent, int, int)} or {@link #onBind(Intent)} depending on how the
 * external protocol works.
 */
/* package */ abstract class ExternalReceiver extends Service {

    @VisibleForTesting
    static final int JOB_FINISHED = 1;

    private static final String TAG = "FJD.ExternalReceiver";

    /**
     * A mapping of service name (i.e. class names) to (local) binder connections.
     */
    private final SimpleArrayMap<String, JobServiceConnection> serviceConnections =
        new SimpleArrayMap<>();
    private ResponseHandler responseHandler = new ResponseHandler(this);

    private void onJobFinishedMessage(JobParameters jobParameters, int result) {
        JobServiceConnection connection;
        synchronized (serviceConnections) {
            connection = serviceConnections.get(jobParameters.getService());
        }

        connection.onJobFinished(jobParameters);
        if (connection.shouldDie()) {
            unbindService(connection);
            synchronized (serviceConnections) {
                serviceConnections.remove(connection);
            }
        }

        onJobFinished(jobParameters, result);
    }

    protected abstract void onJobFinished(@NonNull JobParameters jobParameters, @JobResult int result);

    /**
     * Executes the provided JobParameters. Should be called by subclasses once a JobParameters has been
     * extracted from the incoming {@link Intent}.
     *
     * @return true if the broadcast was sent successfully.
     */
    protected final boolean executeJob(JobParameters jobParameters) {
        if (jobParameters == null) {
            return false;
        }

        JobServiceConnection conn = new JobServiceConnection(jobParameters,
            responseHandler.obtainMessage(JOB_FINISHED));

        serviceConnections.put(jobParameters.getService(), conn);

        bindService(createBindIntent(jobParameters), conn, BIND_AUTO_CREATE);

        return true;
    }

    @NonNull
    private Intent createBindIntent(JobParameters jobParameters) {
        Intent execReq = new Intent(JobService.ACTION_EXECUTE);
        execReq.setClassName(this, jobParameters.getService());
        return execReq;
    }

    private static class ResponseHandler extends Handler {

        private final ExternalReceiver receiver;

        private ResponseHandler(ExternalReceiver receiver) {
            this.receiver = receiver;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case JOB_FINISHED:
                    if (msg.obj instanceof JobParameters) {
                        receiver.onJobFinishedMessage((JobParameters) msg.obj, msg.arg1);
                        return;
                    }

                    Log.wtf(TAG, "handleMessage: unknown obj returned");
                    return;

                default:
                    Log.wtf(TAG, "handleMessage: unknown message type received: " + msg.what);

            }

        }
    }

    private static class JobServiceConnection implements ServiceConnection {
        private final static int NOT_STARTED = 1;
        private final static int RUNNING = 2;

        private final SimpleArrayMap<JobParameters, Integer> jobSpecs = new SimpleArrayMap<>(1);
        private final Message message;
        private boolean isBound = false;
        private JobService.LocalBinder binder;
        private JobServiceConnection(JobParameters jobParameters, Message message) {
            this.message = message;
            this.jobSpecs.put(jobParameters, NOT_STARTED);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!(service instanceof JobService.LocalBinder)) {
                Log.w(TAG, "Unknown service connected");
                return;
            }

            isBound = true;
            binder = (JobService.LocalBinder) service;

            JobService jobService = binder.getService();
            synchronized (jobSpecs) {
                for (int i = 0; i < jobSpecs.size(); i++) {
                    JobParameters job = jobSpecs.keyAt(i);
                    Integer status = jobSpecs.get(job);
                    if (status == NOT_STARTED) {
                        Message copiedMessage = Message.obtain(message);
                        copiedMessage.obj = job;
                        jobService.start(job, copiedMessage);
                    }
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            isBound = false;
        }

        public void onJobFinished(JobParameters jobParameters) {
            synchronized (jobSpecs) {
                jobSpecs.remove(jobParameters);
            }
        }

        public boolean shouldDie() {
            synchronized (jobSpecs) {
                return jobSpecs.isEmpty();
            }
        }
    }
}
