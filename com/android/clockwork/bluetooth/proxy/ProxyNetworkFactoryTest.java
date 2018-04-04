package com.android.clockwork.bluetooth.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo.DetailedState;
import android.os.RemoteException;
import com.android.clockwork.WearRobolectricTestRunner;
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

/** Test for {@link ProxyNetworkFactory} */
@RunWith(WearRobolectricTestRunner.class)
@Config(manifest = Config.NONE,
        shadows = {ShadowNetworkInfo.class },
        sdk = 26)
public class ProxyNetworkFactoryTest {
    private static final String COMPANION_NAME = "CompanionName";
    private static final String REASON = "Reason";
    private static final boolean LE_DEVICE = true;
    private static final boolean ROW_DEVICE = !LE_DEVICE;
    private static final boolean METERED = true;
    private static final boolean NOT_METERED = !METERED;
    private static final int NETWORK_SCORE = 50;
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

    private ProxyNetworkAgent mProxyNetworkAgent;

    private Context mContext;
    private ProxyNetworkFactoryTestClass mProxyNetworkFactory;
    private InetAddress mInetAddress;

    @Mock Context mockContext;
    @Mock IndentingPrintWriter mockIndentingPrintWriter;
    @Mock NetworkCapabilities mockCapabilities;
    @Mock ProxyLinkProperties mockProxyLinkProperties;
    @Mock ProxyNetworkAgent mockProxyNetworkAgent;
    @Mock ProxyNetworkFactory.ProxyNetworkCallback mockProxyNetworkCallback;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mContext = shadowApplication.getApplicationContext();

        mProxyNetworkAgent = new ProxyNetworkAgent();
        mProxyNetworkFactory = createProxyNetworkFactory();
        assertEquals(1, mProxyNetworkFactory.registerMethod);
    }

    @Test
    public void testStartNetwork() {
        mProxyNetworkFactory.startNetwork();
        verify(mockProxyNetworkCallback).onStartNetworkFactory();
    }

    @Test
    public void testStopNetwork() {
        mProxyNetworkFactory.stopNetwork();
        verify(mockProxyNetworkCallback).onStopNetworkFactory();
    }

    @Test
    public void testMaybeSetUpNetworkAgent() {
        mProxyNetworkFactory.maybeSetUpNetworkAgent(REASON, COMPANION_NAME, NETWORK_SCORE);
        assertEquals(1, mProxyNetworkFactory.setScoreFilterMethod);
        assertEquals(NETWORK_SCORE, mProxyNetworkFactory.scoreFilter);
        verify(mockProxyNetworkAgent).setUpNetworkAgent(anyObject(), anyString(), anyObject(),
                anyObject(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    public void testSetUpNetworkAgent() {
        mProxyNetworkFactory.setUpNetworkAgent(REASON, COMPANION_NAME, NETWORK_SCORE);
        assertEquals(1, mProxyNetworkFactory.setScoreFilterMethod);
        assertEquals(NETWORK_SCORE, mProxyNetworkFactory.scoreFilter);
        verify(mockProxyNetworkAgent).setUpNetworkAgent(anyObject(), anyString(), anyObject(),
                anyObject(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    public void testSetNetworkScore_SameScore() {
        mProxyNetworkFactory.setNetworkScore(0);
        assertEquals(0, mProxyNetworkFactory.setScoreFilterMethod);
        assertEquals(0, mProxyNetworkFactory.scoreFilter);
        verify(mockProxyNetworkAgent, never()).sendNetworkScore(anyInt());
    }

    @Test
    public void testSetNetworkScore_GreaterScore() {
        mProxyNetworkFactory.setNetworkScore(NETWORK_SCORE);
        assertEquals(1, mProxyNetworkFactory.setScoreFilterMethod);
        assertEquals(NETWORK_SCORE, mProxyNetworkFactory.scoreFilter);
        verify(mockProxyNetworkAgent).sendNetworkScore(NETWORK_SCORE);
    }

    @Test
    public void testSetNetworkScore_LesserScore() {
        mProxyNetworkFactory.setNetworkScore(NETWORK_SCORE);
        mProxyNetworkFactory.setNetworkScore(0);

        assertEquals(1, mProxyNetworkFactory.setScoreFilterMethod);
        assertEquals(NETWORK_SCORE, mProxyNetworkFactory.scoreFilter);
        verify(mockProxyNetworkAgent, times(2)).sendNetworkScore(anyInt());
    }

    @Test
    public void testSetConnecting() {
        mProxyNetworkFactory.setConnecting(REASON, COMPANION_NAME);
        verify(mockProxyNetworkAgent).setCurrentNetworkInfo(DetailedState.CONNECTING, REASON,
                COMPANION_NAME);
    }

    @Test
    public void testSetConnected() {
        mProxyNetworkFactory.setConnected(REASON, COMPANION_NAME);
        verify(mockProxyNetworkAgent).setCurrentNetworkInfo(DetailedState.CONNECTED, REASON,
                COMPANION_NAME);
    }

    @Test
    public void testSetDisconnected() {
        mProxyNetworkFactory.setDisconnected(REASON, COMPANION_NAME);
        verify(mockProxyNetworkAgent).setCurrentNetworkInfo(DetailedState.DISCONNECTED, REASON,
                COMPANION_NAME);
    }

    @Test
    public void testSendCapabilities() {
        mProxyNetworkFactory.sendCapabilities(mockCapabilities);
        verify(mockProxyNetworkAgent).sendCapabilities(mockCapabilities);
    }

    @Test
    public void testDump() {
        mProxyNetworkFactory.dump(mockIndentingPrintWriter);
        verify(mockProxyNetworkAgent).dump(mockIndentingPrintWriter);
    }

    private ProxyNetworkFactoryTestClass createProxyNetworkFactory() {
        ProxyNetworkFactoryTestClass proxyNetworkFactory
            = new ProxyNetworkFactoryTestClass(
                    mockContext,
                    mockCapabilities,
                    mockProxyLinkProperties,
                    mockProxyNetworkCallback,
                    mockProxyNetworkAgent);
        return proxyNetworkFactory;
    }

    private class ProxyNetworkFactoryTestClass extends ProxyNetworkFactory {
        public int registerMethod;
        public int setScoreFilterMethod;
        public int scoreFilter;

        public ProxyNetworkFactoryTestClass(
                Context context,
                NetworkCapabilities capabilities,
                ProxyLinkProperties proxyLinkProperties,
                ProxyNetworkCallback proxyNetworkCallback,
                ProxyNetworkAgent proxyNetworkAgent) {
            super(context, capabilities, proxyLinkProperties, proxyNetworkCallback,
                    proxyNetworkAgent);
        }

        @Override
        public void register() {
            registerMethod  += 1;
        }

        @Override
        public void setScoreFilter(int score) {
            scoreFilter = score;
            setScoreFilterMethod += 1;
        }
    }
}
