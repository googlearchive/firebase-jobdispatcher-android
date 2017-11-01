package com.firebase.jobdispatcher;

import android.os.Bundle;

oneway interface IJobCallback {
    void jobFinished(in Bundle invocationData, int result) = 0;
}
