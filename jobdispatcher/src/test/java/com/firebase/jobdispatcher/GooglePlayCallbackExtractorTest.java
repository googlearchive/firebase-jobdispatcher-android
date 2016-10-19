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
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import com.google.android.gms.gcm.INetworkTaskCallback;
import com.google.android.gms.gcm.PendingCallback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(constants = BuildConfig.class, sdk = 23)
public final class GooglePlayCallbackExtractorTest {
    @Mock
    private IBinder mBinder;

    private GooglePlayCallbackExtractor mExtractor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mExtractor = new GooglePlayCallbackExtractor();
    }

    @Test
    public void testExtractCallback_nullBundle() {
        assertNull(mExtractor.extractCallback(null));
    }

    @Test
    public void testExtractCallback_nullParcelable() {
        Bundle emptyBundle = new Bundle();
        assertNull(mExtractor.extractCallback(emptyBundle));
    }

    @Test
    public void testExtractCallback_badParcelable() {
        Bundle misconfiguredBundle = new Bundle();
        misconfiguredBundle.putParcelable("callback", new BadParcelable(1));

        assertNull(mExtractor.extractCallback(misconfiguredBundle));
    }

    @Test
    public void testExtractCallback_goodParcelable() {
        Parcel container = Parcel.obtain();
        container.writeStrongBinder(new NopCallback());
        PendingCallback pcb = new PendingCallback(container);

        Bundle validBundle = new Bundle();
        validBundle.putParcelable("callback", pcb);

        assertNotNull(mExtractor.extractCallback(validBundle));

        container.recycle();
    }

    private final static class BadParcelable implements Parcelable {
        public static final Parcelable.Creator<BadParcelable> CREATOR
            = new Parcelable.Creator<BadParcelable>() {
                @Override
                public BadParcelable createFromParcel(Parcel in) {
                    return new BadParcelable(in);
                }

                @Override
                public BadParcelable[] newArray(int size) {
                    return new BadParcelable[size];
                }
        };
        private final int mNum;

        public BadParcelable(int i) {
            mNum = i;
        }

        private BadParcelable(Parcel in) {
            mNum = in.readInt();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dst, int flags) {
            dst.writeInt(mNum);
        }
    }

    public final static class NopCallback extends INetworkTaskCallback.Stub {
        @Override
        public void taskFinished(int result) throws RemoteException {
            // nop
        }
    }
}
