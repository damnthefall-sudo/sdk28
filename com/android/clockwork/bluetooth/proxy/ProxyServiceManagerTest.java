package com.android.clockwork.bluetooth.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.NetworkCapabilities;
import android.os.RemoteException;
import com.android.clockwork.WearRobolectricTestRunner;
import com.android.clockwork.bluetooth.ShadowBluetoothAdapter;
import com.android.internal.util.IndentingPrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

/** Test for {@link ProxyServiceManager} */
@RunWith(WearRobolectricTestRunner.class)
@Config(manifest = Config.NONE,
        shadows = {ShadowBluetoothAdapter.class},
        sdk = 26)
public class ProxyServiceManagerTest {
    private static final boolean LE_DEVICE = true;
    private static final boolean ROW_DEVICE = !LE_DEVICE;
    private static final boolean METERED = true;
    private static final boolean NOT_METERED = !METERED;
    private static final int NETWORK_TYPE_PROXY = 16;
    private static final String INTERFACE_NAME = "lo";

    private static final List<InetAddress> ROW_DNS = new ArrayList<InetAddress>();
    private static final List<InetAddress> LE_DNS = new ArrayList<InetAddress>();

    static {
        try {
            ROW_DNS.add(InetAddress.getByName("8.8.8.8"));
            ROW_DNS.add(InetAddress.getByName("8.8.4.4"));
            LE_DNS.add(InetAddress.getByName("202.106.0.20"));
            LE_DNS.add(InetAddress.getByName("8.8.8.8"));
            LE_DNS.add(InetAddress.getByName("8.8.4.4"));
        } catch (UnknownHostException e) {
            fail(e.getMessage());
        }
    }

    private final ShadowApplication shadowApplication = ShadowApplication.getInstance();

    private Context mContext;
    private ProxyServiceManagerTestClass mProxyServiceManager;
    private InetAddress mInetAddress;

    @Mock Context mockContext;
    @Mock IndentingPrintWriter mockIndentingPrintWriter;
    @Mock NetworkCapabilities mockCapabilities;
    @Mock ProxyLinkProperties mockProxyLinkProperties;
    @Mock ProxyNetworkFactory mockProxyNetworkFactory;
    @Mock ProxyServiceManager.ProxyServiceCallback mockProxyServiceCallback;
    @Mock ProxyServiceManager.ProxyServiceCallback mockProxyServiceCallbackDifferent;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mContext = shadowApplication.getApplicationContext();
        mockCapabilities = mock(NetworkCapabilities.class);

        mProxyServiceManager = createProxyServiceManager();
    }

    @Test
    public void testStartNetwork() {
        mProxyServiceManager.setCallback(mockProxyServiceCallback);
        mProxyServiceManager.startNetwork("CompanionName", 55, "Reason");

        verify(mockProxyNetworkFactory).setUpNetworkAgent(anyString(), anyString(), anyInt());
        verify(mockProxyServiceCallback).onStartNetwork();
    }

    @Test
    public void testStartNetwork_NoCallback() {
        mProxyServiceManager.setCallback(null);
        mProxyServiceManager.startNetwork("CompanionName", 55, "Reason");

        verify(mockProxyNetworkFactory).setUpNetworkAgent(anyString(), anyString(), anyInt());
        verify(mockProxyServiceCallback, never()).onStartNetwork();
    }

    @Test
    public void testStartNetworkFactory() {
        mProxyServiceManager.setCallback(mockProxyServiceCallback);
        mProxyServiceManager.onStartNetworkFactory();

        verify(mockProxyServiceCallback).onStartNetwork();
    }

    @Test
    public void testStartNetworkFactory_NoCallback() {
        mProxyServiceManager.setCallback(null);
        mProxyServiceManager.onStartNetworkFactory();

        verify(mockProxyServiceCallback, never()).onStartNetwork();
    }

    @Test
    public void testStopNetworkFactory() {
        mProxyServiceManager.setCallback(mockProxyServiceCallback);
        mProxyServiceManager.onStopNetworkFactory();

        verify(mockProxyServiceCallback).onStopNetwork();
    }

    @Test
    public void testStopNetworkFactory_NoCallback() {
        mProxyServiceManager.setCallback(null);
        mProxyServiceManager.onStopNetworkFactory();

        verify(mockProxyServiceCallback, never()).onStopNetwork();
    }

    @Test
    public void testEnsureValidNetworkAgent() {
        mProxyServiceManager.ensureValidNetworkAgent("Reason");
        verify(mockProxyNetworkFactory).maybeSetUpNetworkAgent(anyString(), isNull(), anyInt());
    }

    @Test
    public void testSetNetworkScore() {
        mProxyServiceManager.setNetworkScore(55);
        verify(mockProxyNetworkFactory).setNetworkScore(anyInt());
        assertEquals(55, mProxyServiceManager.getNetworkScore());
    }

    @Test
    public void testSetConnecting() {
        mProxyServiceManager.setConnecting("Reason");
        verify(mockProxyNetworkFactory).setConnecting(anyString(), isNull());
    }

    @Test
    public void testSetConnected() {
        mProxyServiceManager.setConnected("Reason");
        verify(mockProxyNetworkFactory).setConnected(anyString(), isNull());
    }

    @Test
    public void testSetDisconnected() {
        mProxyServiceManager.setDisconnected("Reason");
        verify(mockProxyNetworkFactory).setDisconnected(anyString(), isNull());
    }

    @Test
    public void testSetCallback() {
        mProxyServiceManager.setCallback(mockProxyServiceCallback);
        // Set it again
        mProxyServiceManager.setCallback(mockProxyServiceCallback);
        // Set it again
        mProxyServiceManager.setCallback(null);
        mProxyServiceManager.setCallback(mockProxyServiceCallbackDifferent);
   }

    @Test
    public void testSetMetered() {
        mProxyServiceManager.setMetered(true);
        verify(mockCapabilities).removeCapability(anyInt());
        verify(mockProxyNetworkFactory).sendCapabilities(anyObject());
    }

    @Test
    public void testSetUnMetered() {
        mProxyServiceManager.setMetered(false);
        verify(mockCapabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        verify(mockProxyNetworkFactory).sendCapabilities(anyObject());
    }

    @Test
    public void testDump() {
        mProxyServiceManager.dump(mockIndentingPrintWriter);
        verify(mockProxyNetworkFactory).dump(mockIndentingPrintWriter);
    }

    private ProxyServiceManagerTestClass createProxyServiceManager() {
        ProxyServiceManagerTestClass proxyServiceManager
            = new ProxyServiceManagerTestClass(
                    mockContext,
                    mockCapabilities,
                    mockProxyLinkProperties);
        return proxyServiceManager;
    }

    private class ProxyServiceManagerTestClass extends ProxyServiceManager {
        public ProxyServiceManagerTestClass(
                final Context context,
                final NetworkCapabilities capabilities,
                final ProxyLinkProperties proxyLinkProperties) {
            super(context, capabilities, proxyLinkProperties);
        }

        @Override
        protected ProxyNetworkFactory getProxyNetworkFactory(
                final Context context,
                final NetworkCapabilities capabilities,
                final ProxyLinkProperties proxyLinkProperties) {
            return mockProxyNetworkFactory;
        }

        @Override
        protected void addNetworkCapabilitiesBandwidth() { }
     }
}
