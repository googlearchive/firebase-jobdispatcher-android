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

import android.os.IBinder;
import android.os.Parcel;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowParcel;

/**
 * ShadowParcel doesn't correctly handle {@link Parcel#writeStrongBinder(IBinder)} or {@link
 * Parcel#readStrongBinder()}, so we shim a simple implementation that uses an in-memory map to read
 * and write Binder objects.
 */
@Implements(Parcel.class)
public class ExtendedShadowParcel extends ShadowParcel {
  @RealObject private Parcel realObject;

  // Map each IBinder to an integer, and use the super's int-writing capability to fake Binder
  // read/writes.
  private final AtomicInteger nextBinderId = new AtomicInteger(1);
  private final Map<Integer, IBinder> binderMap =
      Collections.synchronizedMap(new HashMap<Integer, IBinder>());

  @Implementation
  public void writeStrongBinder(IBinder binder) {
    int id = nextBinderId.getAndIncrement();
    binderMap.put(id, binder);
    realObject.writeInt(id);
  }

  @Implementation
  public IBinder readStrongBinder() {
    return binderMap.get(realObject.readInt());
  }
}
