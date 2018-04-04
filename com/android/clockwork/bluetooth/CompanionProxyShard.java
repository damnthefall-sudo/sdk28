package com.android.clockwork.bluetooth;

import android.annotation.AnyThread;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothManagerCallback;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.ParcelUuid;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import com.android.clockwork.bluetooth.proxy.ProxyServiceManager;
import com.android.clockwork.bluetooth.proxy.WearProxyConstants.Reason;
import com.android.clockwork.common.DebugAssert;
import com.android.clockwork.common.Util;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import java.io.Closeable;
import java.lang.reflect.Method;

/**
 * Manages connections to the companion sysproxy network
 *
 * This class handles connecting to the remote device using the
 * bluetooth network and configuring the sysproxy to setup the
 * proper network to allow IP traffic to be utilized by Android.
 *
 * Steps to connect to the companion sysproxy.
 *
 * 1. Get a bluetooth rfcomm socket.
 *      This will actually establish a bluetooth connection from the device to the companion.
 * 2. Pass this rfcomm socket to the sysproxy module.
 *      The sysproxy module will formulate the necessary network configuration to allow
 *      IP traffic to flow over the bluetooth socket connection.
 * 3. Get acknowledgement that the sysproxy module initialized.
 *      This may or may not be completed successfully as indicated by the jni callback
 *      indicating connection or failure.
 *
 */
public class CompanionProxyShard implements Closeable, ProxyServiceManager.ProxyServiceCallback {
    private static final String TAG = WearBluetoothConstants.LOG_TAG;
    private static final int WHAT_START_SYSPROXY = 1;
    private static final int WHAT_STOP_SYSPROXY = 2;
    private static final int WHAT_JNI_CONNECTED = 3;
    private static final int WHAT_JNI_DISCONNECTED = 4;
    private static final int WHAT_CONNECTION_FAILED = 5;
    private static final int WHAT_RESET_CONNECTION = 6;

    private static final int INVALID_NETWORK_TYPE = -1;
    private static final int TYPE_RFCOMM = 1;
    private static final int SEC_FLAG_ENCRYPT = 1 << 0;
    private static final int SEC_FLAG_AUTH = 1 << 1;
    // Relative unitless network retry values
    private static final int BACKOFF_BASE_INTERVAL = 2;
    private static final int BACKOFF_BASE_PERIOD = 5;
    private static final int BACKOFF_MAX_INTERVAL = 300;
    private static final ParcelUuid PROXY_UUID =
        ParcelUuid.fromString("fafbdd20-83f0-4389-addf-917ac9dae5b2");

    private static int sInstance;
    private final int mInstance;
    private int mStartAttempts;

    static native void classInitNative();
    @VisibleForTesting native boolean connectNative(int fd);
    @VisibleForTesting native boolean disconnectNative();

    static {
        try {
            System.loadLibrary("wear-bluetooth-jni");
            classInitNative();
        } catch (UnsatisfiedLinkError e) {
            // Invoked during testing
            Log.e(TAG, "Unable to load wear bluetooth sysproxy jni native"
                    + " libraries");
        }
    }

    @NonNull private final Context mContext;
    @NonNull private final BluetoothDevice mCompanionDevice;
    @NonNull private final Listener mListener;
    @NonNull private final ProxyServiceManager mProxyServiceManager;

    private final MultistageExponentialBackoff mReconnectBackoff;
    @VisibleForTesting boolean mIsClosed;

    /** State of sysproxy module process
     *
     * This is a static field because the instance gets
     * created and destroyed by the upper layer based upon
     * specific conditions (e.g. GMS core connected or not)
     *
     * As each instance is created the state of the JNI sysproxy
     * is unknown, so the state of the previous instance is kept here.
     */
    @VisibleForTesting
    static enum State {
        SYSPROXY_DISCONNECTED,  // IDLE or JNI callback
        BLUETOOTH_SOCKET_REQUESTING, // Background thread
        BLUETOOTH_SOCKET_RETRIEVED,
        SYSPROXY_SOCKET_DELIVERING, // Background thread
        SYSPROXY_SOCKET_DELIVERED,
        SYSPROXY_CONNECTED, // JNI callback
        SYSPROXY_DISCONNECT_REQUEST,
        SYSPROXY_DISCONNECT_RESPONSE,
    }

    @VisibleForTesting
    static ProxyState mState = new ProxyState(State.SYSPROXY_DISCONNECTED);

    @VisibleForTesting
    static class ProxyState {
        private State mState;

        public ProxyState(final State state) {
            mState = state;
        }

        @MainThread
        public boolean checkState(final State state) {
            DebugAssert.isMainThread();
            return mState == state;
        }

        @MainThread
        public void advanceState(final int instance, final State state) {
            DebugAssert.isMainThread();
            mState = state;
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "CompanionProxyShard [ " + instance + " ] Set new companion proxy"
                        + " state:" + mState);
            }
        }

        @AnyThread
        public State current() {
            return mState;
        }
    }

    /**
     * Callback executed when the sysproxy becomes connected or disconnected
     *
     * This may send duplicate disconnect events, because failed reconnect
     * attempts are indistinguishable from actual disconnects.
     * Listeners should appropriately deduplicate these disconnect events.
     */
    public interface Listener {
        void onProxyConnectionChange(boolean isConnected, int proxyScore);
    }

    public CompanionProxyShard(
            @NonNull final Context context,
            @NonNull final ProxyServiceManager proxyServiceManager,
            @NonNull final BluetoothDevice companionDevice,
            @NonNull final Listener listener) {
            DebugAssert.isMainThread();

            mContext = context;
            mProxyServiceManager = proxyServiceManager;
            mCompanionDevice = companionDevice;
            mListener = listener;

            mProxyServiceManager.setCallback(this);
            mReconnectBackoff = new MultistageExponentialBackoff(BACKOFF_BASE_INTERVAL,
                    BACKOFF_BASE_PERIOD, BACKOFF_MAX_INTERVAL);

            mInstance = sInstance++;
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] Created companion proxy"
                        + " shard");
            }
        }

    /** Completely shuts down companion proxy network */
    @MainThread
    @Override  // Closable
    public void close() {
        DebugAssert.isMainThread();
        if (mIsClosed) {
            Log.w(TAG, "CompanionProxyShard [ " + mInstance + " ] Already closed");
            return;
        }
        mProxyServiceManager.setCallback(null);
        disconnectAndNotify(Reason.CLOSABLE);
        // notify mListeners of our intended disconnect before setting mIsClosed to true
        mIsClosed = true;
        disconnectNativeInBackground();
        mHandler.removeMessages(WHAT_START_SYSPROXY);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] Closed companion proxy shard");
        }
    }

    @MainThread
    @Override  // ProxyServiceManager.ProxyServiceCallback
    public void onStartNetwork() {
        DebugAssert.isMainThread();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] onStartNetwork()");
        }
        mHandler.sendEmptyMessage(WHAT_START_SYSPROXY);
    }

    @MainThread
    @Override   // ProxyServiceManager.ProxyServiceCallback
    public void onStopNetwork() {
        DebugAssert.isMainThread();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] onStopNetwork()");
        }
        mHandler.sendEmptyMessage(WHAT_STOP_SYSPROXY);
    }

    @MainThread
    @Override   // ProxyServiceManager.ProxyServiceCallback
    public void onUpdateNetwork(int networkScore) {
        DebugAssert.isMainThread();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] onUpdateNetwork()");

        }
    }

    /** Serialize state change requests here */
    @VisibleForTesting
    final Handler mHandler = new Handler() {
        @MainThread
        @Override
        public void handleMessage(Message msg) {
            DebugAssert.isMainThread();
            switch (msg.what) {
                case WHAT_START_SYSPROXY:
                    mStartAttempts++;
                    mHandler.removeMessages(WHAT_START_SYSPROXY);
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] Request to start"
                                + " companion sysproxy network");
                    }
                    if (companionIsNotAvailable()) {
                        Log.e(TAG, "CompanionProxyShard [ " + mInstance + " ] Unable to start"
                                + " sysproxy bluetooth off or companion unpaired");
                        return;
                    }
                    if (mState.checkState(State.SYSPROXY_CONNECTED)) {
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] companion proxy"
                                    + " network already running set connected");
                        }
                        mProxyServiceManager.ensureValidNetworkAgent("Already Connected");
                        connectAndNotify(Reason.SYSPROXY_WAS_CONNECTED);
                    } else if (mState.checkState(State.SYSPROXY_DISCONNECTED)) {
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] companion proxy"
                                    + " network starting up sysproxy module state:"
                                    + mState.current());
                        }
                        mProxyServiceManager.ensureValidNetworkAgent(Reason.START_SYSPROXY);
                        getBluetoothSocket();
                    } else {
                        final int nextRetry = mReconnectBackoff.getNextBackoff();
                        Log.w(TAG, "CompanionProxyShard [ " + mInstance + " ] network not"
                                + " idle/disconnected state:" + mState.current()
                                + " attempting reconnect in " + nextRetry + " seconds");
                        mHandler.sendEmptyMessageDelayed(WHAT_START_SYSPROXY, nextRetry * 1000);
                    }
                    break;
                case WHAT_STOP_SYSPROXY:
                    // If not closed from the upper layer, the proxy will always try maintain a
                    // connection.  However, when the connectivity service decides this network
                    // is unwanted we must break down the current network agent and re-attach
                    // launch with a new network agent.
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] Stop companion proxy"
                                + " network");
                    }
                    if (mState.checkState(State.SYSPROXY_CONNECTED)) {
                        disconnectAndNotify(Reason.STOP_SYSPROXY);
                    } else {
                        Log.w(TAG, "CompanionProxyShard [ " + mInstance + " ] companion proxy"
                                + " network not connected! state:" + mState.current());
                    }
                    break;
                case WHAT_JNI_CONNECTED:
                    mReconnectBackoff.reset();
                    mState.advanceState(mInstance, State.SYSPROXY_CONNECTED);
                    break;
                case WHAT_JNI_DISCONNECTED:
                    final int status = msg.arg1;
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] JNI onDisconnect"
                                + " sysproxy closed mIsClosed:" + mIsClosed + " status:" + status);
                    }
                    if (!mIsClosed && mState.current() != State.SYSPROXY_DISCONNECTED) {
                        disconnectAndNotify(Reason.SYSPROXY_DISCONNECTED);
                    }
                    mState.advanceState(mInstance, State.SYSPROXY_DISCONNECTED);
                    break;
                case WHAT_CONNECTION_FAILED:
                    if (mState.checkState(State.SYSPROXY_CONNECTED)) {
                        Log.e(TAG, "CompanionProxyShard [ " + mInstance + " ] JNI sysproxy failed"
                                + " state:" + mState.current());
                    } else {
                        Log.e(TAG, "CompanionProxyShard [ " + mInstance + " ] JNI sysproxy unable"
                                + " to connect state:" + mState.current());
                    }
                    // fall through
                case WHAT_RESET_CONNECTION:
                    // Take a hammer to reset everything on sysproxy side to initial state.
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] Reset companion proxy"
                                + " network connection last state:" + mState.current()
                                + " isClosed:" + mIsClosed);
                    }
                    mHandler.removeMessages(WHAT_START_SYSPROXY);
                    mHandler.removeMessages(WHAT_RESET_CONNECTION);
                    disconnectNativeInBackground();
                    // Setup a reconnect sequence if shard has not been closed.
                    if (!mIsClosed) {
                        final int nextRetry = mReconnectBackoff.getNextBackoff();
                        mHandler.sendEmptyMessageDelayed(WHAT_START_SYSPROXY, nextRetry * 1000);
                        Log.w(TAG, "CompanionProxyShard [ " + mInstance + " ] Proxy reset"
                                + " Attempting reconnect in " + nextRetry + " seconds");
                    }
                    break;
            }
        }
    };

    /** Use binder API to directly request rfcomm socket from bluetooth module */
    @MainThread
    private void getBluetoothSocket() {
        DebugAssert.isMainThread();

        mState.advanceState(mInstance, State.BLUETOOTH_SOCKET_REQUESTING);
        mProxyServiceManager.setConnecting(Reason.REQUEST_BT_SOCKET);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] Retrieving bluetooth network"
                    + " socket");
        }

        new DefaultPriorityAsyncTask<Void, Void, ParcelFileDescriptor>() {
            @Override
            protected ParcelFileDescriptor doInBackgroundDefaultPriority() {
                try {
                    final IBluetooth bluetoothProxy = getBluetoothService(mInstance);
                    if (bluetoothProxy == null) {
                        Log.e(TAG, "CompanionProxyShard [ " + mInstance + " ] Unable to get binder"
                                + " proxy to IBluetooth");
                        return null;
                    }
                    ParcelFileDescriptor parcelFd = bluetoothProxy.getSocketManager().connectSocket(
                            mCompanionDevice,
                            TYPE_RFCOMM,
                            PROXY_UUID,
                            0 /* port */,
                            SEC_FLAG_AUTH | SEC_FLAG_ENCRYPT
                            );

                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] parcelFd:"
                                + parcelFd);
                    }

                    return parcelFd;
                } catch (RemoteException e) {
                    Log.e(TAG, "CompanionProxyShard [ " + mInstance + " ] Unable to get bluetooth"
                            + " service", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(@Nullable ParcelFileDescriptor parcelFd) {
                DebugAssert.isMainThread();

                if (mIsClosed) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] Shard closed after"
                                + " retrieving bluetooth socket");
                    }
                    return;
                } else if (parcelFd != null) {
                    if (!mState.checkState(State.BLUETOOTH_SOCKET_REQUESTING)) {
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] unexpected state"
                                    + " after retrieving bluetooth network socket state:"
                                    + mState.current());
                        }
                    } else {
                        mState.advanceState(mInstance, State.BLUETOOTH_SOCKET_RETRIEVED);
                        final int fd = parcelFd.detachFd();
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] Retrieved"
                                    + " bluetooth network socket state:" + mState.current()
                                    + " parcelFd:" + parcelFd + " fd:" + fd);
                        }
                        connectNativeInBackground(fd);
                    }
                } else {
                    Log.e(TAG, "CompanionProxyShard [ " + mInstance + " ] Unable to request"
                            + "bluetooth network socket");
                    mHandler.sendEmptyMessage(WHAT_RESET_CONNECTION);
                }
                Util.close(parcelFd);
            }
        }.execute();
    }

    @MainThread
    private void connectNativeInBackground(Integer fd) {
        DebugAssert.isMainThread();
        mState.advanceState(mInstance, State.SYSPROXY_SOCKET_DELIVERING);
        mProxyServiceManager.setConnecting(Reason.DELIVER_BT_SOCKET);

        new ConnectSocketAsyncTask() {
            @Override
            protected Boolean doInBackgroundDefaultPriority(Integer fileDescriptor) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                final int fd = fileDescriptor.intValue();
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] connectNativeInBackground"
                            + " state:" + mState.current() + " fd:" + fd);
                }
                final boolean rc = connectNative(fd);
                return new Boolean(rc);
            }

            @Override
            protected void onPostExecute(Boolean result) {
                DebugAssert.isMainThread();
                if (mIsClosed) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] Shard closed after"
                                + "sending bluetooth socket");
                    }
                    return;
                }
                if (result) {
                    if (!mState.checkState(State.SYSPROXY_SOCKET_DELIVERING)) {
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] unexpected state"
                                    + " after delivering socket state:" + mState.current()
                                    + " fd:" + fd);
                        }
                    } else {
                        mState.advanceState(mInstance, State.SYSPROXY_SOCKET_DELIVERED);
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] proxy socket"
                                    + " delivered state:" + mState.current() + " fd:" + fd);
                        }
                    }
                } else {
                    Log.w(TAG, "CompanionProxyShard [ " + mInstance + " ] Unable to deliver socket"
                            + " to sysproxy module");
                    mHandler.sendEmptyMessage(WHAT_RESET_CONNECTION);
                }
            }
        }.execute(fd);
    }

    /** This call should be idempotent to always ensure proper initial state */
    @MainThread
    private void disconnectNativeInBackground() {
        DebugAssert.isMainThread();
        if (mState.checkState(State.SYSPROXY_DISCONNECTED)) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] JNI has already"
                        + " disconnected");
            }
            return;
        }

        mState.advanceState(mInstance, State.SYSPROXY_DISCONNECT_REQUEST);
        new DefaultPriorityAsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackgroundDefaultPriority() {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] JNI Disconnect request to"
                            + " sysproxy module");
                }
                return disconnectNative();
            }

            @MainThread
            @Override
            protected void onPostExecute(Boolean result) {
                DebugAssert.isMainThread();
                if (result) {
                    mState.advanceState(mInstance, State.SYSPROXY_DISCONNECT_RESPONSE);
                } else {
                    mState.advanceState(mInstance, State.SYSPROXY_DISCONNECTED);
                }
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] JNI Disconnect response"
                            + " result:" + result + " mIsClosed:" + mIsClosed);
                }
            }
        }.execute();
    }

    /**
     * This method is called from JNI in a background thread when the companion proxy
     * network state changes on the phone.
     */
    @WorkerThread
    protected void onActiveNetworkState(final int networkType, final boolean isMetered) {
        if (mIsClosed) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] JNI onActiveNetworkState"
                        + " shard closed...bailing");
            }
            return;
        }

        if (networkType == INVALID_NETWORK_TYPE) {
            mHandler.sendEmptyMessage(WHAT_CONNECTION_FAILED);
        } else {
            connectAndNotify(Reason.SYSPROXY_CONNECTED);
            mProxyServiceManager.setMetered(isMetered);
            mHandler.sendEmptyMessage(WHAT_JNI_CONNECTED);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "CompanionProxyShard [ " + mInstance + " ] JNI sysproxy process complete"
                        + " state:" + mState.current() + " networkType:" + networkType + " metered:"
                        + isMetered);
            }
        }
    }

    /** This method is called from JNI in a background thread when the proxy has disconnected. */
    @WorkerThread
    protected void onDisconnect(int status) {
        mHandler.sendMessage(mHandler.obtainMessage(WHAT_JNI_DISCONNECTED, status, 0));
    }

    /**
     * These methods notify connectivity service and the upper layers
     * with the current sysproxy state.
     */
    @AnyThread
    private void connectAndNotify(final String reason) {
        mProxyServiceManager.setConnected(reason);
        notifyConnectionChange(true);
    }

    @AnyThread
    private void disconnectAndNotify(final String reason) {
        mProxyServiceManager.setDisconnected(reason);
        notifyConnectionChange(false);
    }

    /**
     * This method notifies mListeners about the state of the sysproxy network.
     *
     *  NOTE: CompanionProxyShard should never call onProxyConnectionChange directly!
     *       Use the notifyConnectionChange method instead.
     */
    @AnyThread
    private void notifyConnectionChange(final boolean isConnected) {
        if (!mIsClosed) {
            mListener.onProxyConnectionChange(isConnected, mProxyServiceManager.getNetworkScore());
        }
    }

    /** Check if bluetooth is on and companion paired before connecting to sysproxy */
    private boolean companionIsNotAvailable() {
        return !isBluetoothOn() || companionHasBecomeUnpaired();
    }

    private boolean companionHasBecomeUnpaired() {
        final boolean unpaired = mCompanionDevice.getBondState() == BluetoothDevice.BOND_NONE;
        if (unpaired) {
            Log.w(TAG, "CompanionProxyShard [ " + mInstance + " ] Companion has become unpaired");
        }
        return unpaired;
    }

    private boolean isBluetoothOn() {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.isEnabled()) {
            return true;
        }
        Log.w(TAG, "CompanionProxyShard [ " + mInstance + " ] Bluetooth adapter is off or in"
                + " unknown state");
        return false;
    }

    private abstract static class DefaultPriorityAsyncTask<Params, Progress, Result>
            extends AsyncTask<Params, Progress, Result> {

            @Override
            protected Result doInBackground(Params... params) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                return doInBackgroundDefaultPriority();
            }

            protected abstract Result doInBackgroundDefaultPriority();
    }

    private abstract static class ConnectSocketAsyncTask extends AsyncTask<Integer, Void, Boolean> {
        @Override
        protected Boolean doInBackground(Integer... params) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            final Integer fileDescriptor = params[0];
            return doInBackgroundDefaultPriority(fileDescriptor);
        }

        protected abstract Boolean doInBackgroundDefaultPriority(Integer fd);
    }

    /** Returns the shared instance of IBluetooth using reflection (method is package private). */
    private static IBluetooth getBluetoothService(final int instance) {
        try {
            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            final Method getBluetoothService = adapter.getClass()
                .getDeclaredMethod("getBluetoothService", IBluetoothManagerCallback.class);
            getBluetoothService.setAccessible(true);
            return (IBluetooth) getBluetoothService.invoke(adapter, new Object[] { null });
        } catch (Exception e) {
            Log.e(TAG, "CompanionProxyShard [ " + instance + " ] Error retrieving IBluetooth: ", e);
            return null;
        }
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append("CompanionProxyShard: " + mInstance);
        return result.toString();
    }

    public void dump(@NonNull final IndentingPrintWriter ipw) {
        ipw.printf("Companion proxy instance:%d companion device:%s\n", mInstance,
                mCompanionDevice);
        ipw.increaseIndent();
        ipw.printPair("Current state", mState.current());
        ipw.printPair("Is closed", mIsClosed);
        ipw.printPair("Start attempts", mStartAttempts);
        ipw.printPair("Start connection scheduled",
                mHandler.hasMessages(WHAT_START_SYSPROXY));
        ipw.println();
        ipw.decreaseIndent();
        mProxyServiceManager.dump(ipw);
    }
}
