package com.android.clockwork.bluetooth.proxy;

import static com.android.clockwork.bluetooth.proxy.WearProxyConstants.WEAR_NETWORK_SUBTYPE;
import static com.android.clockwork.bluetooth.proxy.WearProxyConstants.WEAR_NETWORK_SUBTYPE_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.os.Looper;
import android.util.Log;
import com.android.clockwork.common.DebugAssert;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link NetworkAgent} that represents bluetooth companion proxy networks.
 *
 * Interacts with {@link ConnectivityService} and {@link NetworkFactory} to provide
 * proxy network connectivity over bluetooth.
 *
 * Proxy network agent {@link NetworkAgent} container that handles bluetooth proxy requests.
 *
 * When a network agent beocmes unwanted it is put into the unwanted set until reaped.
 */
public class ProxyNetworkAgent {
    private static final String TAG = WearProxyConstants.LOG_TAG;
    private static final String NETWORK_AGENT_NAME = "CompanionProxyAgent";
    static final boolean ALWAYS_CREATE_AGENT = true;
    static final boolean MAYBE_RECYCLE_AGENT = false;

    @VisibleForTesting
    @Nullable NetworkAgent mCurrentNetworkAgent;

    @VisibleForTesting
    final HashMap<NetworkAgent, NetworkInfo> mNetworkAgents
        = new HashMap<NetworkAgent, NetworkInfo>();

    /** Create a idle proxy network info object */
    private static final NetworkInfo IDLE_NETWORK = new NetworkInfo(ConnectivityManager.TYPE_PROXY,
            WEAR_NETWORK_SUBTYPE, WearProxyConstants.NETWORK_TYPE, WEAR_NETWORK_SUBTYPE_NAME);

    protected synchronized void setCurrentNetworkInfo(DetailedState detailedState, String reason,
            String extraInfo) {
        if (mCurrentNetworkAgent != null) {
            final NetworkInfo networkInfo = mNetworkAgents.get(mCurrentNetworkAgent);
            networkInfo.setDetailedState(detailedState, reason, extraInfo);
            mNetworkAgents.put(mCurrentNetworkAgent, networkInfo);
            mCurrentNetworkAgent.sendNetworkInfo(networkInfo);
        } else {
            Log.w(TAG, "Send network info with no network agent reason:"
                    + reason);
        }
    }

    protected synchronized void sendCapabilities(final NetworkCapabilities capabilities) {
        if (mCurrentNetworkAgent != null) {
            mCurrentNetworkAgent.sendNetworkCapabilities(capabilities);
        } else {
            Log.w(TAG, "Send capabilities with no network agent");
        }
    }

    protected synchronized void sendNetworkScore(final int networkScore) {
        if (mCurrentNetworkAgent != null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Set network score for agent:" + mCurrentNetworkAgent.netId);
            }
            mCurrentNetworkAgent.sendNetworkScore(networkScore);
        } else {
            Log.w(TAG, "Send network score with no network agent");
        }
    }

    /**
     * Create a network agent for the proxy.
     *
     * The {@link NetworkAgent} constructor connects that objects handler with
     * {@link ConnectiviyService} in order to provide proxy network access.
     *
     * If there are no clients who want this network this agent will be torn down.
     */
    protected synchronized void setUpNetworkAgent(
            final Context context,
            final String reason,
            final NetworkCapabilities capabilities,
            final ProxyLinkProperties proxyLinkProperties,
            final int networkScore,
            final String companionName,
            final boolean forceNewAgent) {
        DebugAssert.isMainThread();
        if (mCurrentNetworkAgent != null) {
            if (forceNewAgent) {
                Log.w(TAG, "Setup Overwriting current network agent since"
                        + " one already existed ... previous agent:" + mCurrentNetworkAgent.netId);
            } else {
                Log.w(TAG, "SetUp recycling existing network agent:"
                        + mCurrentNetworkAgent.netId);
                return;
            }
        }

        final NetworkInfo networkInfo = new NetworkInfo(IDLE_NETWORK);
        networkInfo.setIsAvailable(true);
        networkInfo.setDetailedState(DetailedState.CONNECTING, reason, companionName);
        mCurrentNetworkAgent = new NetworkAgent(
                Looper.getMainLooper(),
                context,
                NETWORK_AGENT_NAME,
                networkInfo,
                capabilities,
                proxyLinkProperties.getLinkProperties(),
                networkScore) {
            @Override
            protected void unwanted() {
                DebugAssert.isMainThread();
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Proxy network can no longer satisfy"
                            + " any network requests...tearing down network agent:" + this.netId);
                }
                tearDownNetworkAgent(this);
            }
        };
        mNetworkAgents.put(mCurrentNetworkAgent, networkInfo);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Created network agent:" + mCurrentNetworkAgent.netId);
        }
    }

    private synchronized void tearDownNetworkAgent(
            @NonNull final NetworkAgent unwantedNetworkAgent) {
        DebugAssert.isMainThread();
        final NetworkInfo networkInfo = mNetworkAgents.get(unwantedNetworkAgent);
        if (networkInfo == null) {
            Log.e(TAG, "Unable to find unwanted network agent in map"
                    + " network agent:" + unwantedNetworkAgent.netId);
            return;
        }
        networkInfo.setDetailedState(DetailedState.DISCONNECTED, "unwanted",
                networkInfo.getExtraInfo());
        unwantedNetworkAgent.sendNetworkInfo(networkInfo);
        mNetworkAgents.remove(unwantedNetworkAgent);
        if (unwantedNetworkAgent == mCurrentNetworkAgent) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "disconnected unwanted current network agent:"
                        + mCurrentNetworkAgent.netId);
            }
            mCurrentNetworkAgent = null;
        } else {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "unwanted network agent already torn down and"
                        + " replaced with new one unwanted:" + unwantedNetworkAgent.netId
                        + " agent:" + mCurrentNetworkAgent.netId);
            }
        }
    }

    public void dump(@NonNull final IndentingPrintWriter ipw) {
        ipw.printPair("Network agent id", (mCurrentNetworkAgent == null)
                ? "null" : mCurrentNetworkAgent.netId);
        ipw.increaseIndent();
        for (Map.Entry<NetworkAgent, NetworkInfo> entry : mNetworkAgents.entrySet()) {
            ipw.printPair(entry.getKey().toString(), entry.getValue());
        }
        ipw.decreaseIndent();
        ipw.println();
    }
}
