package com.android.clockwork.bluetooth.proxy;

import android.annotation.AnyThread;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo.DetailedState;
import android.os.Looper;
import android.util.Log;
import com.android.clockwork.common.DebugAssert;
import com.android.internal.util.IndentingPrintWriter;

/**
 * {@link NetworkFactory} that represents bluetooth companion proxy networks.
 *
 * Handles callbacks from {@link ConnectivityService} to start and stop the network.
 *
 */
class ProxyNetworkFactory extends NetworkFactory {
    private static final String TAG = WearProxyConstants.LOG_TAG;

    @NonNull private final Context mContext;
    @NonNull private final NetworkCapabilities mCapabilities;
    @NonNull private final ProxyLinkProperties mProxyLinkProperties;
    @NonNull private final ProxyNetworkCallback mProxyNetworkCallback;
    @NonNull private final ProxyNetworkAgent mProxyNetworkAgent;

    private int mNetworkScoreFilter;

    /** Triggers when {@link ConnectivityService} requests action */
    public interface ProxyNetworkCallback {
        public void onStartNetworkFactory();
        public void onStopNetworkFactory();
    }

    protected ProxyNetworkFactory(
            @NonNull final Context context,
            @NonNull final NetworkCapabilities capabilities,
            @NonNull final ProxyLinkProperties proxyLinkProperties,
            @NonNull final ProxyNetworkCallback proxyNetworkCallback,
            @NonNull final ProxyNetworkAgent proxyNetworkAgent) {
        super(Looper.getMainLooper(), context, WearProxyConstants.NETWORK_TYPE, capabilities);
        DebugAssert.isMainThread();

        mContext = context;
        mCapabilities = capabilities;
        mProxyLinkProperties = proxyLinkProperties;
        mProxyNetworkCallback = proxyNetworkCallback;
        mProxyNetworkAgent = proxyNetworkAgent;

        // This is in a system service so we never unregister() the factory
        register();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Created proxy network factory");
        }
    }

    @Override  // ConnectivityService request via NetworkFactory handler
    protected void startNetwork() {
        DebugAssert.isMainThread();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "startNetwork Connectivity service is starting network");
        }
        mProxyNetworkCallback.onStartNetworkFactory();
    }

    @Override  // ConnectivityService request via NetworkFactory handler
    protected void stopNetwork() {
        DebugAssert.isMainThread();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Connectivity service stopping network");
        }
        mProxyNetworkCallback.onStopNetworkFactory();
    }

    @MainThread
    void maybeSetUpNetworkAgent(
            @Nullable final String reason,
            @Nullable final String companionName,
            final int networkScore) {
        doSetUpNetworkAgent(reason, companionName, networkScore,
                ProxyNetworkAgent.MAYBE_RECYCLE_AGENT);
    }

    @MainThread
    void setUpNetworkAgent(
            @Nullable final String reason,
            @Nullable final String companionName,
            final int networkScore) {
        doSetUpNetworkAgent(reason, companionName, networkScore,
                ProxyNetworkAgent.ALWAYS_CREATE_AGENT);
    }

    /**
     * Create or recycle network agent
     *
     * We want to re-use the state of any existing network agents, most of the time.
     * We want to create a new one when during initial start up in the companion proxy shard.
     */
    private void doSetUpNetworkAgent(
            @Nullable final String reason,
            @Nullable final String companionName,
            final int networkScore,
            final boolean recycleOrCreateAgent) {
        setScoreFilter(networkScore);
        mProxyNetworkAgent.setUpNetworkAgent(
                mContext,
                reason,
                mCapabilities,
                mProxyLinkProperties,
                networkScore,
                companionName,
                recycleOrCreateAgent);
    }

    /** Invoking this may force a network evaluation of default network
     *
     * This factory will advertise the highest possible score that
     * this network is capable of delivering.
     *
     * If the factory network score goes up, network requests may be fulfilled by
     * this bearer factory network during the evaluation phase.  This results in
     * the proxy network being started up.
     *
     * If the factory network score goes down, existing network requests accustomed
     * to the higher score that are connected will be stopped and re-evaluated to
     * see which requests may be fulfilled by the bearer.
     *
     * If the proxy network factory maintains the highest factory network score filter
     * the current connections remain connected and are not re-evaluated and the
     * network is not stopped.
     *
     */
    @AnyThread
    protected void setNetworkScore(final int networkScore) {
        if (mNetworkScoreFilter != networkScore) {
            if (networkScore > mNetworkScoreFilter) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Changing network score filter from " + mNetworkScoreFilter
                            + " to " + mNetworkScoreFilter);
                }
                mNetworkScoreFilter = networkScore;
                setScoreFilter(mNetworkScoreFilter);
            }
            mProxyNetworkAgent.sendNetworkScore(networkScore);
        }
    }

    @AnyThread
    protected void setConnecting(@Nullable final String reason,
            @Nullable final String companionName) {
        mProxyNetworkAgent.setCurrentNetworkInfo(DetailedState.CONNECTING, reason, companionName);
    }

    @AnyThread
    protected void setConnected(@Nullable final String reason,
            @Nullable final String companionName) {
        mProxyNetworkAgent.setCurrentNetworkInfo(DetailedState.CONNECTED, reason, companionName);
    }

    @AnyThread
    protected void setDisconnected(@Nullable final String reason,
            @Nullable final String companionName) {
        mProxyNetworkAgent.setCurrentNetworkInfo(DetailedState.DISCONNECTED, reason, companionName);
    }

    @MainThread
    protected void sendCapabilities(NetworkCapabilities mCapabilities) {
        mProxyNetworkAgent.sendCapabilities(mCapabilities);
    }

    public void dump(@NonNull final IndentingPrintWriter ipw) {
        ipw.printPair("Network score filter", mNetworkScoreFilter);
        ipw.printPair("Network mCapabilities", mCapabilities);
        ipw.printPair("Network link properties", mProxyLinkProperties.getLinkProperties());
        ipw.println();
        mProxyNetworkAgent.dump(ipw);
    }
}
