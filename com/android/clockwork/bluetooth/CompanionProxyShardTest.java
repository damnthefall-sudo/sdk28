package com.android.clockwork.bluetooth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.MockBluetoothProxyHelper;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import com.android.clockwork.WearRobolectricTestRunner;
import com.android.clockwork.bluetooth.proxy.ProxyServiceManager;
import com.android.internal.util.IndentingPrintWriter;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.List;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;

/** Test for Companion proxy shard */
@RunWith(WearRobolectricTestRunner.class)
@Config(manifest = Config.NONE,
        shadows = {ShadowBluetoothAdapter.class },
        sdk = 26)
public class CompanionProxyShardTest {
    final ShadowApplication shadowApplication = ShadowApplication.getInstance();

    private static final int INSTANCE = -1;
    private static final int FD = 2;

    private static final int WHAT_START_SYSPROXY = 1;
    private static final int WHAT_STOP_SYSPROXY = 2;
    private static final int WHAT_JNI_CONNECTED = 3;
    private static final int WHAT_JNI_DISCONNECTED = 4;
    private static final int WHAT_CONNECTION_FAILED = 5;
    private static final int WHAT_RESET_CONNECTION = 6;

    private static final int INVALID_NETWORK_TYPE = -1;

    private Context mContext;

    private CompanionProxyShardTestClass mCompanionProxyShard;
    private CompanionProxyShardListener mCompanionProxyShardListener
        = new CompanionProxyShardListener();

    private MockBluetoothProxyHelper mBluetoothProxyHelper;

    @Mock BluetoothAdapter mockBluetoothAdapter;
    @Mock BluetoothDevice mockBluetoothDevice;
    @Mock Context mockContext;
    @Mock IndentingPrintWriter mockIndentingPrintWriter;
    @Mock ParcelFileDescriptor mockParcelFileDescriptor;
    @Mock ProxyServiceManager mockProxyServiceManager;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mContext = shadowApplication.getApplicationContext();
        mCompanionProxyShard = createCompanionProxyShard();

        when(mockBluetoothAdapter.isEnabled()).thenReturn(true);
        mBluetoothProxyHelper = new MockBluetoothProxyHelper(mockBluetoothAdapter);

        ShadowBluetoothAdapter.setAdapter(mockBluetoothAdapter);
        mCompanionProxyShard.mState.advanceState(INSTANCE,
            CompanionProxyShard.State.SYSPROXY_DISCONNECTED);
        when(mockParcelFileDescriptor.detachFd()).thenReturn(FD);
    }

    @Test
    public void testStartNetwork_StateSysproxyDisconnected() {
        mBluetoothProxyHelper.setMockParcelFileDescriptor(mockParcelFileDescriptor);
        mCompanionProxyShard.mState.advanceState(INSTANCE,
            CompanionProxyShard.State.SYSPROXY_DISCONNECTED);

        mCompanionProxyShard.onStartNetwork();

        assertEquals(1, mCompanionProxyShard.connectNativeCount);
        verify(mockParcelFileDescriptor, times(1)).detachFd();

        // Simulate JNI callback
        mCompanionProxyShard.simulateJniCallbackConnect(1, false);

        assertEquals(CompanionProxyShard.State.SYSPROXY_CONNECTED,
            mCompanionProxyShard.mState.current());
        verify(mockProxyServiceManager, times(2)).setConnecting(anyString());
        verify(mockProxyServiceManager).setConnected(anyString());
        assertTrue(mCompanionProxyShardListener.isConnected);
        assertEquals(0, mCompanionProxyShardListener.proxyScore);

        ensureMessageQueueEmpty();
    }

    @Test
    public void testStartNetwork_StateSysproxyConnected() {
        mBluetoothProxyHelper.setMockParcelFileDescriptor(mockParcelFileDescriptor);
        mCompanionProxyShard.mState.advanceState(INSTANCE,
                CompanionProxyShard.State.SYSPROXY_CONNECTED);

        mCompanionProxyShard.onStartNetwork();
        assertEquals(0, mCompanionProxyShard.connectNativeCount);

        verify(mockParcelFileDescriptor, never()).detachFd();

        assertEquals(CompanionProxyShard.State.SYSPROXY_CONNECTED,
                mCompanionProxyShard.mState.current());
        verify(mockProxyServiceManager, never()).setConnecting(anyString());
        verify(mockProxyServiceManager).setConnected(anyString());
        assertTrue(mCompanionProxyShardListener.isConnected);
        assertEquals(0, mCompanionProxyShardListener.proxyScore);

        ensureMessageQueueEmpty();
    }

    @Test
    public void testStartNetwork_ConnectedOnStop() {
        mBluetoothProxyHelper.setMockParcelFileDescriptor(mockParcelFileDescriptor);
        mCompanionProxyShard.mState.advanceState(INSTANCE,
            CompanionProxyShard.State.SYSPROXY_DISCONNECTED);

        mCompanionProxyShard.onStartNetwork();

        assertEquals(1, mCompanionProxyShard.connectNativeCount);
        verify(mockParcelFileDescriptor, times(1)).detachFd();

        // Simulate JNI callback
        mCompanionProxyShard.simulateJniCallbackConnect(1, false);

        assertEquals(CompanionProxyShard.State.SYSPROXY_CONNECTED,
            mCompanionProxyShard.mState.current());
        verify(mockProxyServiceManager, times(2)).setConnecting(anyString());
        verify(mockProxyServiceManager).setConnected(anyString());
        assertTrue(mCompanionProxyShardListener.isConnected);
        assertEquals(0, mCompanionProxyShardListener.proxyScore);

        mCompanionProxyShard.onStopNetwork();

        assertFalse(mCompanionProxyShardListener.isConnected);
        verify(mockProxyServiceManager).setDisconnected(anyString());
    }

    @Test
    public void testStartNetwork_ClosedAndConnected() {
        mBluetoothProxyHelper.setMockParcelFileDescriptor(mockParcelFileDescriptor);
        mCompanionProxyShard.mState.advanceState(INSTANCE,
            CompanionProxyShard.State.SYSPROXY_CONNECTED);

        mCompanionProxyShard.mIsClosed = true;
        mCompanionProxyShard.onStartNetwork();

        verify(mockProxyServiceManager, times(1)).ensureValidNetworkAgent(anyString());
        verify(mockProxyServiceManager, never()).getNetworkScore();
    }

    @Test
    public void testStartNetwork_NullParcelFileDescriptor() {
        ShadowLooper.pauseMainLooper();
        mBluetoothProxyHelper.setMockParcelFileDescriptor(null);
        mCompanionProxyShard.mState.advanceState(INSTANCE,
            CompanionProxyShard.State.SYSPROXY_DISCONNECTED);

        mCompanionProxyShard.onStartNetwork();
        ShadowLooper.runMainLooperOneTask();
        ShadowLooper.runMainLooperOneTask();

        verify(mockProxyServiceManager, times(1)).ensureValidNetworkAgent(anyString());
        assertTrue(mCompanionProxyShard.mHandler.hasMessages(WHAT_RESET_CONNECTION));
    }

    @Test
    public void testStartNetwork_DisconnectedOnStop() {
        mBluetoothProxyHelper.setMockParcelFileDescriptor(mockParcelFileDescriptor);

        mCompanionProxyShard.onStopNetwork();

        assertFalse(mCompanionProxyShardListener.isConnected);
        verify(mockProxyServiceManager, never()).setDisconnected(anyString());
    }

    @Test
    public void testUpdateNetwork() {
        mBluetoothProxyHelper.setMockParcelFileDescriptor(mockParcelFileDescriptor);

        mCompanionProxyShard.onUpdateNetwork(100);
    }

    @Test
    public void testStartNetwork_StateSysproxyDisconnectRequest() {
        mBluetoothProxyHelper.setMockParcelFileDescriptor(mockParcelFileDescriptor);
        mCompanionProxyShard.mState.advanceState(INSTANCE,
                CompanionProxyShard.State.SYSPROXY_DISCONNECT_REQUEST);

        mCompanionProxyShard.onStartNetwork();
        assertEquals(0, mCompanionProxyShard.connectNativeCount);

        verify(mockParcelFileDescriptor, never()).detachFd();

        assertEquals(CompanionProxyShard.State.SYSPROXY_DISCONNECT_REQUEST,
                mCompanionProxyShard.mState.current());
        // Ensure the proper message queue state
        assertTrue(mCompanionProxyShard.mHandler.hasMessages(WHAT_START_SYSPROXY));
    }

    @Test
    public void testStartNetwork_StateSysproxyDisconnectedAndUnpaired() {
        mBluetoothProxyHelper.setMockParcelFileDescriptor(mockParcelFileDescriptor);
        mCompanionProxyShard.mState.advanceState(INSTANCE,
                CompanionProxyShard.State.SYSPROXY_DISCONNECTED);
        when(mockBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_NONE);

        mCompanionProxyShard.onStartNetwork();

        assertEquals(0, mCompanionProxyShard.connectNativeCount);
        verify(mockParcelFileDescriptor, never()).detachFd();

        assertEquals(CompanionProxyShard.State.SYSPROXY_DISCONNECTED,
                mCompanionProxyShard.mState.current());

        ensureMessageQueueEmpty();
    }

    @Test
    public void testStartNetwork_AdapterIsNull() {
        // Force bluetooth adapter to return null
        ShadowBluetoothAdapter.forceNull = true;
        mCompanionProxyShard.mState.advanceState(INSTANCE,
                CompanionProxyShard.State.SYSPROXY_DISCONNECTED);

        ShadowLooper.pauseMainLooper();
        mCompanionProxyShard.onStartNetwork();

        ShadowLooper.runMainLooperOneTask();

        assertEquals(CompanionProxyShard.State.SYSPROXY_DISCONNECTED,
                mCompanionProxyShard.mState.current());
        ensureMessageQueueEmpty();
        // Restore bluetooth adapter to return a valid instance
        ShadowBluetoothAdapter.forceNull = false;
     }

    @Test
    public void testStartNetwork_AdapterIsDisabled() {
        mCompanionProxyShard.mState.advanceState(INSTANCE,
                CompanionProxyShard.State.SYSPROXY_DISCONNECTED);

        when(mockBluetoothAdapter.isEnabled()).thenReturn(false);

        mCompanionProxyShard.onStartNetwork();

        ShadowLooper.runMainLooperOneTask();

        assertEquals(CompanionProxyShard.State.SYSPROXY_DISCONNECTED,
                mCompanionProxyShard.mState.current());
        ensureMessageQueueEmpty();
     }

    @Test
    public void testStartNetwork_BluetoothServiceIsNull() {
        mBluetoothProxyHelper.setBluetoothService(null);
        mCompanionProxyShard.mState.advanceState(INSTANCE,
                CompanionProxyShard.State.SYSPROXY_DISCONNECTED);

        ShadowLooper.pauseMainLooper();
        mCompanionProxyShard.onStartNetwork();

        ShadowLooper.runMainLooperOneTask();
        ShadowLooper.runMainLooperOneTask();

        assertEquals(CompanionProxyShard.State.BLUETOOTH_SOCKET_REQUESTING,
                mCompanionProxyShard.mState.current());
        assertTrue(mCompanionProxyShard.mHandler.hasMessages(WHAT_RESET_CONNECTION));
     }

    @Test
    public void testJniActiveNetworkState_AlreadyClosed() {
        mCompanionProxyShard.mIsClosed = true;
        mCompanionProxyShard.simulateJniCallbackConnect(1, true);
        assertTrue(ShadowLooper.getMainLooper().myQueue().isIdle());
    }

    @Test
    public void testJniActiveNetworkState_InvalidNetworkType() {
        ShadowLooper.pauseMainLooper();
        mCompanionProxyShard.simulateJniCallbackConnect(INVALID_NETWORK_TYPE, true);
        assertTrue(mCompanionProxyShard.mHandler.hasMessages(WHAT_CONNECTION_FAILED));
    }

    @Test
    public void testJniActiveNetworkState_ConnectionFailedConnected() {
        ShadowLooper.pauseMainLooper();
        mCompanionProxyShard.mState.advanceState(INSTANCE,
                CompanionProxyShard.State.SYSPROXY_CONNECTED);
        mCompanionProxyShard.simulateJniCallbackConnect(INVALID_NETWORK_TYPE, true);
        assertTrue(mCompanionProxyShard.mHandler.hasMessages(WHAT_CONNECTION_FAILED));

        ShadowLooper.runMainLooperOneTask();
        assertTrue(mCompanionProxyShard.mHandler.hasMessages(WHAT_START_SYSPROXY));
        assertFalse(mCompanionProxyShard.mHandler.hasMessages(WHAT_CONNECTION_FAILED));
    }

    @Test
    public void testJniActiveNetworkState_ConnectionFailedNotConnected() {
        ShadowLooper.pauseMainLooper();
        mCompanionProxyShard.mState.advanceState(INSTANCE,
                CompanionProxyShard.State.SYSPROXY_DISCONNECTED);
        mCompanionProxyShard.simulateJniCallbackConnect(INVALID_NETWORK_TYPE, true);
        assertTrue(mCompanionProxyShard.mHandler.hasMessages(WHAT_CONNECTION_FAILED));

        ShadowLooper.runMainLooperOneTask();
        assertTrue(mCompanionProxyShard.mHandler.hasMessages(WHAT_START_SYSPROXY));
        assertFalse(mCompanionProxyShard.mHandler.hasMessages(WHAT_CONNECTION_FAILED));
    }

    @Test
    public void testStartNetwork_JniDisconnectNotClosed() {
        mBluetoothProxyHelper.setMockParcelFileDescriptor(mockParcelFileDescriptor);
        mCompanionProxyShard.mState.advanceState(INSTANCE,
                CompanionProxyShard.State.SYSPROXY_DISCONNECTED);

        mCompanionProxyShard.onStartNetwork();

        assertEquals(1, mCompanionProxyShard.connectNativeCount);
        verify(mockParcelFileDescriptor, times(1)).detachFd();

        // Simulate JNI connect callback
        mCompanionProxyShard.simulateJniCallbackConnect(1, false);

        assertEquals(CompanionProxyShard.State.SYSPROXY_CONNECTED,
                mCompanionProxyShard.mState.current());
        verify(mockProxyServiceManager).setConnected(anyString());
        assertTrue(mCompanionProxyShardListener.isConnected);
        assertEquals(0, mCompanionProxyShardListener.proxyScore);

        // Simulate JNI disconnect callback
        mCompanionProxyShard.simulateJniCallbackDisconnect(-1);

        assertEquals(CompanionProxyShard.State.SYSPROXY_DISCONNECTED,
                mCompanionProxyShard.mState.current());
        assertFalse(mCompanionProxyShardListener.isConnected);
        verify(mockProxyServiceManager).setDisconnected(anyString());
    }

    @Test
    public void testStartNetwork_JniDisconnectClosed() {
        mBluetoothProxyHelper.setMockParcelFileDescriptor(mockParcelFileDescriptor);
        mCompanionProxyShard.mState.advanceState(INSTANCE,
                CompanionProxyShard.State.SYSPROXY_DISCONNECTED);

        mCompanionProxyShard.onStartNetwork();

        assertEquals(1, mCompanionProxyShard.connectNativeCount);
        verify(mockParcelFileDescriptor, times(1)).detachFd();

        // Simulate JNI connect callback
        mCompanionProxyShard.simulateJniCallbackConnect(1, false);

        assertEquals(CompanionProxyShard.State.SYSPROXY_CONNECTED,
                mCompanionProxyShard.mState.current());
        verify(mockProxyServiceManager).setConnected(anyString());
        assertTrue(mCompanionProxyShardListener.isConnected);
        assertEquals(0, mCompanionProxyShardListener.proxyScore);

        mCompanionProxyShard.mIsClosed = true;

        // Simulate JNI disconnect callback
        mCompanionProxyShard.simulateJniCallbackDisconnect(-1);

        verify(mockProxyServiceManager, never()).setDisconnected(anyString());
    }

    @Test
    public void testStartNetwork_StateSysproxyConnected_ClosedWasConnected() {
        mBluetoothProxyHelper.setMockParcelFileDescriptor(mockParcelFileDescriptor);
        mCompanionProxyShard.mState.advanceState(INSTANCE,
                CompanionProxyShard.State.SYSPROXY_DISCONNECTED);

        mCompanionProxyShard.onStartNetwork();

        assertEquals(1, mCompanionProxyShard.connectNativeCount);
        verify(mockParcelFileDescriptor, times(1)).detachFd();

        // Simulate JNI callback
        mCompanionProxyShard.simulateJniCallbackConnect(1, false);

        assertEquals(CompanionProxyShard.State.SYSPROXY_CONNECTED,
                mCompanionProxyShard.mState.current());
        verify(mockProxyServiceManager).setConnected(anyString());
        assertTrue(mCompanionProxyShardListener.isConnected);
        assertEquals(0, mCompanionProxyShardListener.proxyScore);

        mCompanionProxyShard.close();
        verify(mockProxyServiceManager).setCallback(isNull());
        verify(mockProxyServiceManager).setDisconnected(anyString());
        assertFalse(mCompanionProxyShardListener.isConnected);
        assertTrue(mCompanionProxyShard.mIsClosed);
        assertEquals(1, mCompanionProxyShard.disconnectNativeCount);
        assertEquals(CompanionProxyShard.State.SYSPROXY_DISCONNECT_RESPONSE,
                mCompanionProxyShard.mState.current());
    }

    @Test
    public void testStartNetwork_StateSysproxyConnected_ClosedWasDisconnected() {
        mBluetoothProxyHelper.setMockParcelFileDescriptor(mockParcelFileDescriptor);
        mCompanionProxyShard.mState.advanceState(INSTANCE,
                CompanionProxyShard.State.SYSPROXY_DISCONNECTED);

        mCompanionProxyShard.onStartNetwork();

        assertEquals(1, mCompanionProxyShard.connectNativeCount);
        verify(mockParcelFileDescriptor, times(1)).detachFd();

        // Simulate JNI callback
        mCompanionProxyShard.simulateJniCallbackConnect(1, false);

        assertEquals(CompanionProxyShard.State.SYSPROXY_CONNECTED,
                mCompanionProxyShard.mState.current());
        verify(mockProxyServiceManager).setConnected(anyString());
        assertTrue(mCompanionProxyShardListener.isConnected);
        assertEquals(0, mCompanionProxyShardListener.proxyScore);

        // Set false
        mCompanionProxyShard.disconnectReturnValue = false;

        mCompanionProxyShard.close();
        verify(mockProxyServiceManager).setCallback(isNull());
        verify(mockProxyServiceManager).setDisconnected(anyString());
        assertFalse(mCompanionProxyShardListener.isConnected);
        assertTrue(mCompanionProxyShard.mIsClosed);
        assertEquals(1, mCompanionProxyShard.disconnectNativeCount);
        assertEquals(CompanionProxyShard.State.SYSPROXY_DISCONNECTED,
                mCompanionProxyShard.mState.current());
    }

    @Test
    public void testStartNetwork_StateSysproxyConnected_ClosedWasAlreadyClosed() {
        mBluetoothProxyHelper.setMockParcelFileDescriptor(mockParcelFileDescriptor);
        mCompanionProxyShard.mState.advanceState(INSTANCE,
                CompanionProxyShard.State.SYSPROXY_DISCONNECTED);

        mCompanionProxyShard.mIsClosed = true;

        mCompanionProxyShard.close();

        verify(mockProxyServiceManager, never()).setCallback(isNull());
        verify(mockProxyServiceManager, never()).setDisconnected(anyString());
    }

    @Test
    public void testToString() {
        assertNotEquals("", mCompanionProxyShard.toString());
    }

    @Test
    public void testDump() {
        mCompanionProxyShard.dump(mockIndentingPrintWriter);
    }

    // Create the companion proxy shard to be used in the tests.
    // The class abstracts away dependencies on difficult framework methods and fields.
    private CompanionProxyShardTestClass createCompanionProxyShard() {
        when(mockBluetoothDevice.getBondState()).thenReturn(BluetoothDevice.BOND_BONDED);

        CompanionProxyShardTestClass companionProxyShard
            = new CompanionProxyShardTestClass(mockContext, mockProxyServiceManager,
                    mockBluetoothDevice, mCompanionProxyShardListener);

        return companionProxyShard;
    }

    private void ensureMessageQueueEmpty() {
        for (int i = WHAT_START_SYSPROXY; i <= WHAT_RESET_CONNECTION; i++) {
            assertFalse(mCompanionProxyShard.mHandler.hasMessages(i));
        }
    }

    private class CompanionProxyShardTestClass extends CompanionProxyShard {
        int connectNativeCount;
        int disconnectNativeCount;
        int unregisterCount;

        boolean connectReturnValue = true;
        boolean disconnectReturnValue = true;

        public CompanionProxyShardTestClass(
                final Context context,
                final ProxyServiceManager proxyServiceManager,
                final BluetoothDevice device,
                final Listener listener) {
            super(context, proxyServiceManager, device, listener);
        }

        @Override
        protected boolean connectNative(int fd) {
            connectNativeCount += 1;
            return connectReturnValue;
        }

        void simulateJniCallbackConnect(int networkType, boolean isMetered) {
            super.onActiveNetworkState(networkType, isMetered);
        }

        @Override
        protected boolean disconnectNative() {
            disconnectNativeCount += 1;
            return disconnectReturnValue;
        }

        void simulateJniCallbackDisconnect(int status) {
            super.onDisconnect(status);
        }
    }

    private class CompanionProxyShardListener implements CompanionProxyShard.Listener {
        private boolean isConnected;
        private int proxyScore;

        @Override
        public void onProxyConnectionChange(boolean isConnected, int proxyScore) {
            this.isConnected = isConnected;
            this.proxyScore = proxyScore;
        }
    }

    public static <InetAddress> boolean listEqualsIgnoreOrder(List<InetAddress> list1,
            List<InetAddress> list2) {
        return new HashSet<>(list1).equals(new HashSet<>(list2));
    }
}
