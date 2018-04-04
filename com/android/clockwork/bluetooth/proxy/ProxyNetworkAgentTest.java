package com.android.clockwork.bluetooth.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothSocketManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkAgentHelper; // Testable helper
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.RouteInfo;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import com.android.clockwork.WearRobolectricTestRunner;
import com.android.internal.util.IndentingPrintWriter;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

/** Test for Companion proxy shard */
@RunWith(WearRobolectricTestRunner.class)
@Config(manifest = Config.NONE,
        shadows = {ShadowNetworkInfo.class, ShadowConnectivityManager.class },
        sdk = 26)
public class ProxyNetworkAgentTest {
    final ShadowApplication shadowApplication = ShadowApplication.getInstance();

    private static final int NETWORK_SCORE = 50;

    private static final boolean LE_DEVICE = true;
    private static final boolean ROW_DEVICE = !LE_DEVICE;
    private static final boolean METERED = true;
    private static final boolean NOT_METERED = !METERED;
    private static final boolean FORCE_NEW_AGENT = true;
    private static final boolean MAYBE_USE_EXISTING_NEW_AGENT = !FORCE_NEW_AGENT;

    private static final int NETWORK_TYPE_PROXY = 16;
    private static final String INTERFACE_NAME = "lo";
    private static final String COMPANION_NAME = "Companion Name";

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

    private Context mContext;
    private ProxyNetworkAgent mProxyNetworkAgent;
    private InetAddress mInetAddress;

    @Mock BluetoothAdapter mockBluetoothAdapter;
    @Mock BluetoothDevice mockBluetoothDevice;
    @Mock ConnectivityManager mockConnectivityManager;
    @Mock Context mockContext;
    @Mock IBluetooth mockBluetoothProxy;
    @Mock IBluetoothSocketManager mockBluetoothSocketManager;
    @Mock LinkProperties mockLinkProperties;
    @Mock NetworkAgent mockNetworkAgent;
    @Mock NetworkCapabilities mockCapabilities;
    @Mock NetworkInfo mockNetworkInfo;
    @Mock PackageManager mockPackageManager;
    @Mock ParcelFileDescriptor mockParcelFileDescriptor;
    @Mock IndentingPrintWriter mockIndentingPrintWriter;
    @Mock ProxyLinkProperties mockProxyLinkProperties;
    @Mock ProxyNetworkFactory mockProxyNetworkFactory;
    @Mock ProxyServiceManager mockProxyServiceManager;
    @Mock ProxyServiceManager.ProxyServiceCallback mockProxyServiceCallback;
    @Mock RouteInfo mockRouteInfo;

    private static final String REASON = "Reason";
    private static final String EXTRA_INFO = "Extra Info";

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        when(mockProxyLinkProperties.getLinkProperties()).thenReturn(mockLinkProperties);
        Hashtable<String, LinkProperties> stackedLinks = new Hashtable<String, LinkProperties>();
        try {
            Field field = LinkProperties.class.getDeclaredField("mStackedLinks");
            field.setAccessible(true);
            field.set(mockLinkProperties, stackedLinks);

        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail();
        }

        mContext = ShadowApplication.getInstance().getApplicationContext();
        mProxyNetworkAgent = createProxyNetworkAgent();
    }

    @Test
    public void testSetCurrentNetworkInfo_NoAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = null;
        mProxyNetworkAgent.setCurrentNetworkInfo(DetailedState.CONNECTED, REASON, EXTRA_INFO);

        verify(mockNetworkInfo, never()).setDetailedState(any(), anyString(), anyString());
        assertTrue(mProxyNetworkAgent.mNetworkAgents.isEmpty());
        verify(mockNetworkAgent, never()).sendNetworkInfo(mockNetworkInfo);
    }

    @Test
    public void testSetCurrentNetworkInfo_ExistingAgent() {
        setupNetworkAgent();

        mProxyNetworkAgent.setCurrentNetworkInfo(DetailedState.CONNECTED, REASON, EXTRA_INFO);

        verify(mockNetworkAgent).sendNetworkInfo(mockNetworkInfo);
    }

    @Test
    public void testSendCapabilities_NoAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = null;
        mProxyNetworkAgent.sendCapabilities(mockCapabilities);
        verify(mockNetworkAgent, never()).sendNetworkCapabilities(mockCapabilities);
    }

    @Test
    public void testSendCapabilities_ExistingAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = mockNetworkAgent;
        mProxyNetworkAgent.sendCapabilities(mockCapabilities);
        verify(mockNetworkAgent).sendNetworkCapabilities(mockCapabilities);
    }

    @Test
    public void testSendNetworkScore_NoAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = null;
        mProxyNetworkAgent.sendNetworkScore(NETWORK_SCORE);

        verify(mockNetworkAgent, never()).sendNetworkScore(NETWORK_SCORE);
    }

    @Test
    public void testSendNetworkScore_ExistingAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = mockNetworkAgent;
        mProxyNetworkAgent.sendNetworkScore(NETWORK_SCORE);
        verify(mockNetworkAgent).sendNetworkScore(NETWORK_SCORE);
    }

    @Test
    public void testSetUpNetworkAgent_NoAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = null;

        mProxyNetworkAgent.setUpNetworkAgent(mContext, REASON, mockCapabilities,
                mockProxyLinkProperties, NETWORK_SCORE, COMPANION_NAME, FORCE_NEW_AGENT);
        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
    }

    @Test
    public void testSetUpNetworkAgent_ExistingAgentReUse() {
        setupNetworkAgent();

        mProxyNetworkAgent.setUpNetworkAgent(mContext, REASON, mockCapabilities,
                mockProxyLinkProperties, NETWORK_SCORE, COMPANION_NAME, !FORCE_NEW_AGENT);
        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
        assertEquals(mockNetworkAgent, mProxyNetworkAgent.mCurrentNetworkAgent);
    }

    @Test
    public void testSetUpNetworkAgent_ExistingAgentForceNew() {
        setupNetworkAgent();

        mProxyNetworkAgent.setUpNetworkAgent(mContext, REASON, mockCapabilities,
                mockProxyLinkProperties, NETWORK_SCORE, COMPANION_NAME, FORCE_NEW_AGENT);
        assertEquals(2, mProxyNetworkAgent.mNetworkAgents.size());
        assertNotEquals(mockNetworkAgent, mProxyNetworkAgent.mCurrentNetworkAgent);
    }

    @Test
    public void testTearDownNetworkAgent_NoAgentForceNew() {
        mProxyNetworkAgent.mCurrentNetworkAgent = null;

        mProxyNetworkAgent.setUpNetworkAgent(mContext, REASON, mockCapabilities,
                mockProxyLinkProperties, NETWORK_SCORE, COMPANION_NAME, FORCE_NEW_AGENT);
        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
        assertNotNull(mProxyNetworkAgent.mCurrentNetworkAgent);

        NetworkAgentHelper.callUnwanted(mProxyNetworkAgent.mCurrentNetworkAgent);

        assertTrue(mProxyNetworkAgent.mNetworkAgents.isEmpty());
        assertNull(mProxyNetworkAgent.mCurrentNetworkAgent);
    }

    @Test
    public void testTearDownNetworkAgent_ExistingAgentForceNew() {
        mProxyNetworkAgent.setUpNetworkAgent(mContext, REASON, mockCapabilities,
                mockProxyLinkProperties, NETWORK_SCORE, COMPANION_NAME, FORCE_NEW_AGENT);
        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
        assertNotNull(mProxyNetworkAgent.mCurrentNetworkAgent);

        NetworkAgent unwantedAgent = mProxyNetworkAgent.mCurrentNetworkAgent;

        mProxyNetworkAgent.setUpNetworkAgent(mContext, REASON, mockCapabilities,
                mockProxyLinkProperties, NETWORK_SCORE, COMPANION_NAME, FORCE_NEW_AGENT);
        assertEquals(2, mProxyNetworkAgent.mNetworkAgents.size());

        NetworkAgentHelper.callUnwanted(unwantedAgent);

        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
    }

    @Test
    public void testTearDownNetworkAgent_ExistingAgentForceNewButMissingFromHash() {
        mProxyNetworkAgent.setUpNetworkAgent(mContext, REASON, mockCapabilities,
                mockProxyLinkProperties, NETWORK_SCORE, COMPANION_NAME, FORCE_NEW_AGENT);
        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
        assertNotNull(mProxyNetworkAgent.mCurrentNetworkAgent);

        NetworkAgent unwantedAgent = mProxyNetworkAgent.mCurrentNetworkAgent;

        mProxyNetworkAgent.setUpNetworkAgent(mContext, REASON, mockCapabilities,
                mockProxyLinkProperties, NETWORK_SCORE, COMPANION_NAME, FORCE_NEW_AGENT);
        assertEquals(2, mProxyNetworkAgent.mNetworkAgents.size());

        // Secretly poison the hash here
        mProxyNetworkAgent.mNetworkAgents.remove(unwantedAgent);

        NetworkAgentHelper.callUnwanted(unwantedAgent);

        assertEquals(1, mProxyNetworkAgent.mNetworkAgents.size());
    }


    @Test
    public void testDump_NoAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = null;
        mProxyNetworkAgent.dump(mockIndentingPrintWriter);
        verify(mockIndentingPrintWriter).printPair(anyString(), anyString());
    }

    @Test
    public void testDump_ExistingAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = mockNetworkAgent;
        mProxyNetworkAgent.dump(mockIndentingPrintWriter);
        verify(mockIndentingPrintWriter).printPair(anyString(), anyInt());
    }

    private void setupNetworkAgent() {
        mProxyNetworkAgent.mCurrentNetworkAgent = mockNetworkAgent;
        mProxyNetworkAgent.mNetworkAgents.put(mockNetworkAgent, mockNetworkInfo);
    }

     private ProxyNetworkAgent createProxyNetworkAgent() {
        // SetUp mocks here
        ProxyNetworkAgent proxyNetworkAgent = new ProxyNetworkAgent();

        return proxyNetworkAgent;
    }
}
