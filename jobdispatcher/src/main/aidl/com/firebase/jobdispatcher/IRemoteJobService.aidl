package com.firebase.jobdispatcher;

import android.os.Bundle;
import android.os.Messenger;
import com.firebase.jobdispatcher.IJobCallback;

oneway interface IRemoteJobService {
    void start(in Bundle invocationData, in IJobCallback callback) = 0;
    void stop(in Bundle invocationData, boolean needToSendResult) = 1;
}
