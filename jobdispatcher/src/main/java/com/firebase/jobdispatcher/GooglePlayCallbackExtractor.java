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

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Pair;
import java.util.ArrayList;

/**
 * Responsible for extracting a JobCallback from a given Bundle.
 *
 * <p>Google Play services will send the Binder packed inside a simple strong Binder wrapper ({@link
 * #PENDING_CALLBACK_CLASS}) under the key {@link #BUNDLE_KEY_CALLBACK "callback"}.
 */
/* package */ final class GooglePlayCallbackExtractor {

    private static final String TAG = GooglePlayReceiver.TAG;
    private static final String ERROR_NULL_CALLBACK = "No callback received, terminating";
    private static final String ERROR_INVALID_CALLBACK = "Bad callback received, terminating";

    /** The Parcelable class that wraps the Binder we need to access. */
    private static final String PENDING_CALLBACK_CLASS =
            "com.google.android.gms.gcm.PendingCallback";
    /** The key for the wrapped Binder. */
    private static final String BUNDLE_KEY_CALLBACK = "callback";
    /** A magic number that indicates the following bytes belong to a Bundle. */
    private static final int BUNDLE_MAGIC = 0x4C444E42;
    /** A magic number that indicates the following value is a Parcelable. */
    private static final int VAL_PARCELABLE = 4;

    public Pair<JobCallback, Bundle> extractCallback(@Nullable Bundle data) {
        if (data == null) {
            Log.e(TAG, ERROR_NULL_CALLBACK);
            return null;
        }

        return extractWrappedBinderFromParcel(data);
    }

    /**
     * Bundles are written out in the following format:
     * A header, which consists of:
     * <ol>
     *   <li>length (int)</li>
     *   <li>magic number ({@link #BUNDLE_MAGIC}) (int)</li>
     *   <li>number of entries (int)</li>
     * </ol>
     * <p/>
     * Then the map values, each of which looks like this:
     * <ol>
     *   <li>string key</li>
     *   <li>int type marker</li>
     *   <li>(any) parceled value</li>
     * </ol>
     * <p/>
     * We're just going to iterate over the map looking for the right key (BUNDLE_KEY_CALLBACK)
     * and try and read the IBinder straight from the parcelled data. This is entirely dependent
     * on the implementation of Parcel, but these specific parts of Parcel / Bundle haven't
     * changed since 2008 and newer versions of Android will ship with newer versions of Google
     * Play services which embed the IBinder directly into the Bundle (no need to deal with the
     * Parcelable issues).
     */
    @Nullable
    @SuppressLint("ParcelClassLoader")
    private Pair<JobCallback, Bundle> extractWrappedBinderFromParcel(Bundle data) {
        Bundle cleanBundle = new Bundle();
        Parcel serialized = toParcel(data);
        JobCallback callback = null;

        try {
            int length = serialized.readInt();
            if (length == 0) {
                // Empty Bundle
                return null;
            }

            int magic = serialized.readInt();
            if (magic != BUNDLE_MAGIC) {
                // Not a Bundle
                Log.w(TAG, ERROR_NULL_CALLBACK);
                return null;
            }

            int numEntries = serialized.readInt();
            for (int i = 0; i < numEntries; i++) {
                String entryKey = serialized.readString();
                if (!(callback == null && BUNDLE_KEY_CALLBACK.equals(entryKey))) {
                    // If it's not the 'callback' key, we can just read it using the standard
                    // mechanisms because we're not afraid of rogue BadParcelableExceptions.
                    Object value = serialized.readValue(null /* class loader */);
                    if (value instanceof String) {
                        cleanBundle.putString(entryKey, (String) value);
                    } else if (value instanceof Boolean) {
                        cleanBundle.putBoolean(entryKey, (boolean) value);
                    } else if (value instanceof Integer) {
                        cleanBundle.putInt(entryKey, (int) value);
                    } else if (value instanceof ArrayList) {
                        // The only acceptable ArrayList in a Bundle is one that consists entirely
                        // of Parcelables, so this cast is safe.
                        @SuppressWarnings("unchecked") // safe by specification
                        ArrayList<Parcelable> arrayList = (ArrayList<Parcelable>) value;
                        cleanBundle.putParcelableArrayList(entryKey, arrayList);
                    } else if (value instanceof Bundle) {
                        cleanBundle.putBundle(entryKey, (Bundle) value);
                    } else if (value instanceof Parcelable) {
                        cleanBundle.putParcelable(entryKey, (Parcelable) value);
                    }

                    // Move to the next key
                    continue;
                }

                int typeTag = serialized.readInt();
                if (typeTag != VAL_PARCELABLE) {
                    // If the key is correct ("callback"), but it's not a Parcelable then something
                    // went wrong and we should bail.
                    Log.w(TAG, ERROR_INVALID_CALLBACK);
                    return null;
                }

                String clsname = serialized.readString();
                if (!PENDING_CALLBACK_CLASS.equals(clsname)) {
                    // If it's a Parcelable, but not one we recognize then we should not try and
                    // unpack it.
                    Log.w(TAG, ERROR_INVALID_CALLBACK);
                    return null;
                }

                // Instead of trying to instantiate clsname, we'll just read its single member.
                IBinder remote = serialized.readStrongBinder();
                callback = new GooglePlayJobCallback(remote);
            }

            if (callback == null) {
                Log.w(TAG, ERROR_NULL_CALLBACK);
                return null;
            }
            return Pair.create(callback, cleanBundle);
        } finally {
            serialized.recycle();
        }
    }

    private Parcel toParcel(Bundle data) {
        Parcel serialized = Parcel.obtain();
        data.writeToParcel(serialized, 0);
        serialized.setDataPosition(0);
        return serialized;
    }
}
