/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.internal.os;

import android.os.Binder;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collects statistics about CPU time spent per binder call across multiple dimensions, e.g.
 * per thread, uid or call description.
 */
public class BinderCallsStats {
    private static final int CALL_SESSIONS_POOL_SIZE = 100;
    private static final BinderCallsStats sInstance = new BinderCallsStats();

    private volatile boolean mTrackingEnabled = false;
    private final SparseArray<UidEntry> mUidEntries = new SparseArray<>();
    private final Queue<CallSession> mCallSessionsPool = new ConcurrentLinkedQueue<>();

    private BinderCallsStats() {
    }

    @VisibleForTesting
    public BinderCallsStats(boolean trackingEnabled) {
        mTrackingEnabled = trackingEnabled;
    }

    public CallSession callStarted(Binder binder, int code) {
        if (!mTrackingEnabled) {
            return null;
        }

        return callStarted(binder.getClass().getName(), code);
    }

    private CallSession callStarted(String className, int code) {
        CallSession s = mCallSessionsPool.poll();
        if (s == null) {
            s = new CallSession();
        }
        s.mCallStat.className = className;
        s.mCallStat.msg = code;

        s.mStarted = getThreadTimeMicro();
        return s;
    }

    public void callEnded(CallSession s) {
        if (!mTrackingEnabled) {
            return;
        }
        Preconditions.checkNotNull(s);
        final long cpuTimeNow = getThreadTimeMicro();
        final long duration = cpuTimeNow - s.mStarted;
        s.mCallingUId = Binder.getCallingUid();

        synchronized (mUidEntries) {
            UidEntry uidEntry = mUidEntries.get(s.mCallingUId);
            if (uidEntry == null) {
                uidEntry = new UidEntry(s.mCallingUId);
                mUidEntries.put(s.mCallingUId, uidEntry);
            }

            // Find CallDesc entry and update its total time
            CallStat callStat = uidEntry.mCallStats.get(s.mCallStat);
            // Only create CallStat if it's a new entry, otherwise update existing instance
            if (callStat == null) {
                callStat = new CallStat(s.mCallStat.className, s.mCallStat.msg);
                uidEntry.mCallStats.put(callStat, callStat);
            }
            uidEntry.time += duration;
            uidEntry.callCount++;
            callStat.callCount++;
            callStat.time += duration;
        }
        if (mCallSessionsPool.size() < CALL_SESSIONS_POOL_SIZE) {
            mCallSessionsPool.add(s);
        }
    }

    public void dump(PrintWriter pw) {
        Map<Integer, Long> uidTimeMap = new HashMap<>();
        Map<Integer, Long> uidCallCountMap = new HashMap<>();
        long totalCallsCount = 0;
        long totalCallsTime = 0;
        int uidEntriesSize = mUidEntries.size();
        List<UidEntry> entries = new ArrayList<>();
        synchronized (mUidEntries) {
            for (int i = 0; i < uidEntriesSize; i++) {
                UidEntry e = mUidEntries.valueAt(i);
                entries.add(e);
                totalCallsTime += e.time;
                // Update per-uid totals
                Long totalTimePerUid = uidTimeMap.get(e.uid);
                uidTimeMap.put(e.uid,
                        totalTimePerUid == null ? e.time : totalTimePerUid + e.time);
                Long totalCallsPerUid = uidCallCountMap.get(e.uid);
                uidCallCountMap.put(e.uid, totalCallsPerUid == null ? e.callCount
                        : totalCallsPerUid + e.callCount);
                totalCallsCount += e.callCount;
            }
        }
        pw.println("Binder call stats:");
        pw.println("  Raw data (uid,call_desc,time):");
        entries.sort((o1, o2) -> {
            if (o1.time < o2.time) {
                return 1;
            } else if (o1.time > o2.time) {
                return -1;
            }
            return 0;
        });
        StringBuilder sb = new StringBuilder();
        for (UidEntry uidEntry : entries) {
            List<CallStat> callStats = new ArrayList<>(uidEntry.mCallStats.keySet());
            callStats.sort((o1, o2) -> {
                if (o1.time < o2.time) {
                    return 1;
                } else if (o1.time > o2.time) {
                    return -1;
                }
                return 0;
            });
            for (CallStat e : callStats) {
                sb.setLength(0);
                sb.append("    ")
                        .append(uidEntry.uid).append(",").append(e).append(',').append(e.time);
                pw.println(sb);
            }
        }
        pw.println();
        pw.println("  Per UID Summary(UID: time, total_time_percentage, calls_count):");
        List<Map.Entry<Integer, Long>> uidTotals = new ArrayList<>(uidTimeMap.entrySet());
        uidTotals.sort((o1, o2) -> o2.getValue().compareTo(o1.getValue()));
        for (Map.Entry<Integer, Long> uidTotal : uidTotals) {
            Long callCount = uidCallCountMap.get(uidTotal.getKey());
            pw.println(String.format("    %5d: %11d %3.0f%% %8d",
                    uidTotal.getKey(), uidTotal.getValue(),
                    100d * uidTotal.getValue() / totalCallsTime, callCount));
        }
        pw.println();
        pw.println(String.format("  Summary: total_time=%d, "
                        + "calls_count=%d, avg_call_time=%.0f",
                totalCallsTime, totalCallsCount,
                (double)totalCallsTime / totalCallsCount));
    }

    private static long getThreadTimeMicro() {
        return SystemClock.currentThreadTimeMicro();
    }

    public static BinderCallsStats getInstance() {
        return sInstance;
    }

    public void setTrackingEnabled(boolean enabled) {
        mTrackingEnabled = enabled;
    }

    public boolean isTrackingEnabled() {
        return mTrackingEnabled;
    }

    private static class CallStat {
        String className;
        int msg;
        long time;
        long callCount;

        CallStat() {
        }

        CallStat(String className, int msg) {
            this.className = className;
            this.msg = msg;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            CallStat callStat = (CallStat) o;

            return msg == callStat.msg && (className == callStat.className);
        }

        @Override
        public int hashCode() {
            int result = className.hashCode();
            result = 31 * result + msg;
            return result;
        }

        @Override
        public String toString() {
            return className + "/" + msg;
        }
    }

    public static class CallSession {
        int mCallingUId;
        long mStarted;
        CallStat mCallStat = new CallStat();
    }

    private static class UidEntry {
        int uid;
        long time;
        long callCount;

        UidEntry(int uid) {
            this.uid = uid;
        }

        // Aggregate time spent per each call name: call_desc -> cpu_time_micros
        Map<CallStat, CallStat> mCallStats = new ArrayMap<>();

        @Override
        public String toString() {
            return "UidEntry{" +
                    "time=" + time +
                    ", callCount=" + callCount +
                    ", mCallStats=" + mCallStats +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            UidEntry uidEntry = (UidEntry) o;
            return uid == uidEntry.uid;
        }

        @Override
        public int hashCode() {
            return uid;
        }
    }

}
