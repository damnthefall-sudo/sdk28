package com.android.clockwork.bluetooth.proxy;

import android.annotation.AnyThread;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.util.Log;
import com.android.clockwork.common.DebugAssert;
import com.android.internal.util.IndentingPrintWriter;

/**
 * Manager class that interfaces with {@link ConnectivityService} that handles bluetooth
 * companion proxy networks.
 *
 * Manages network factory and network agents that Interact with {@link ConnectivityService}
 * to provide network connectivity.
 *
 */
public class ProxyServiceManager implements ProxyNetworkFactory.ProxyNetworkCallback {
    private static final String TAG = WearProxyConstants.LOG_TAG;
    private static final int CAPABILITIES_UPSTREAM_BANDWIDTH_KBPS = 1600;
    private static final int CAPABILITIES_DOWNSTREAM_BANDWIDTH_KBPS = 1600;

    private final Context mContext;
    private final NetworkCapabilities mCapabilities;
    private final ProxyLinkProperties mProxyLinkProperties;

    private final ProxyNetworkFactory mProxyNetworkFactory;

    @Nullable private String mCompanionName;
    private int mNetworkScore;

    @Nullable private ProxyServiceCallback mProxyServiceCallback;

    /** Callbacks that indicate change in sysproxy connectivity */
    public interface ProxyServiceCallback {
        public void onStartNetwork();
        public void onStopNetwork();
        public void onUpdateNetwork(int networkScore);
    }

    public ProxyServiceManager(
            final Context context,
            final NetworkCapabilities capabilities,
            final ProxyLinkProperties proxyLinkProperties) {
        DebugAssert.isMainThread();

        mContext = context;
        mCapabilities = capabilities;
        mProxyLinkProperties = proxyLinkProperties;

        buildCapabilities();

        mProxyNetworkFactory = getProxyNetworkFactory(mContext, mCapabilities,
                mProxyLinkProperties);

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Created proxy network service manager");
        }
    }

    protected ProxyNetworkFactory getProxyNetworkFactory(
            final Context mContext,
            final NetworkCapabilities mCapabilities,
            final ProxyLinkProperties mProxyLinkProperties) {
        return new ProxyNetworkFactory(mContext, mCapabilities, mProxyLinkProperties, this,
                new ProxyNetworkAgent());
    }

    /** Wear core service requests proxy network start */
    @MainThread
    public void startNetwork(
            @Nullable final String companionName,
            final int networkScore,
            @Nullable final String reason) {
        mCompanionName = companionName;
        mNetworkScore = networkScore;
        mProxyNetworkFactory.setUpNetworkAgent(reason, mCompanionName, mNetworkScore);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "startNetwork starting network mCompanionName:"
                    + mCompanionName + " mNetworkScore:" + mNetworkScore + " reason:" + reason);
        }
        doStartNetwork(reason);
    }

    /** Connectivity service requests proxy network start */
    @MainThread
    @Override  // ProxyNetworkFactory.ProxyNetworkCallback
    public void onStartNetworkFactory() {
        doStartNetwork("ConnService");
    }

    private void doStartNetwork(@Nullable final String reason) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "doStartNetwork starting network mCompanionName:"
                    + mCompanionName + " reason:" + reason);
        }
        if (mProxyServiceCallback != null) {
            mProxyServiceCallback.onStartNetwork();
        }
    }

    /** Connectivity service requests proxy network stop */
    @MainThread
    @Override  // ProxyNetworkFactory.ProxyNetworkCallback
    public void onStopNetworkFactory() {
        if (mProxyServiceCallback != null) {
            mProxyServiceCallback.onStopNetwork();
        }
    }

    /** The network agent may disappear at any time.
     *
     * The network agent could disconnect for any number of reasons and
     * there is information the sysproxy is trying to provide.
     *
     * Ensure we have a network agent, if one not currently present, to
     * advocate for the sysproxy network in the larger scheme of connectivity
     * service.  Without a network proxy the connectivity service has no
     * state of the sysproxy network.
     *
     */
    @MainThread
    public void ensureValidNetworkAgent(@Nullable final String reason) {
        mProxyNetworkFactory.maybeSetUpNetworkAgent(reason, mCompanionName, mNetworkScore);
    }

    /** Invoking this will force a network evaluation of default network */
    @AnyThread
    public void setNetworkScore(final int networkScore) {
        mNetworkScore = networkScore;
        mProxyNetworkFactory.setNetworkScore(mNetworkScore);
    }

    @AnyThread
    public int getNetworkScore() {
        return mNetworkScore;
    }

    /** Set the current connecting network status */
    @AnyThread
    public void setConnecting(@Nullable final String reason) {
        mProxyNetworkFactory.setConnecting(reason, mCompanionName);
    }

    /** Set the current connected network status */
    @AnyThread
    public void setConnected(@Nullable final String reason) {
        mProxyNetworkFactory.setConnected(reason, mCompanionName);
    }

    /** Set the current disconnected network status */
    @AnyThread
    public void setDisconnected(@Nullable final String reason) {
        mProxyNetworkFactory.setDisconnected(reason, mCompanionName);
    }

    public void setCallback(@NonNull final ProxyServiceCallback proxyServiceCallback) {
        if (mProxyServiceCallback != null) {
            if (proxyServiceCallback != null) {
                Log.w(TAG, "Proxy network callback was already set");
            }
            if (mProxyServiceCallback == proxyServiceCallback) {
                Log.w(TAG, "Set proxy network callback twice with same data");
            }
        }
        mProxyServiceCallback = proxyServiceCallback;
    }

    @MainThread
    public void setMetered(final boolean isMetered) {
        if (isMetered) {
            mCapabilities.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } else {
            mCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        }
        mProxyNetworkFactory.sendCapabilities(mCapabilities);
    }

    private void buildCapabilities() {
        mCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH);
        mCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        mCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        mCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        mCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED);
        addNetworkCapabilitiesBandwidth();
    }

    protected void addNetworkCapabilitiesBandwidth() {
        mCapabilities.setLinkUpstreamBandwidthKbps(CAPABILITIES_UPSTREAM_BANDWIDTH_KBPS);
        mCapabilities.setLinkDownstreamBandwidthKbps(CAPABILITIES_DOWNSTREAM_BANDWIDTH_KBPS);
    }

    public void dump(@NonNull final IndentingPrintWriter ipw) {
        ipw.printPair("companion name", mCompanionName);
        ipw.printPair("network score", mNetworkScore);
        ipw.println();
        mProxyNetworkFactory.dump(ipw);
    }
}
