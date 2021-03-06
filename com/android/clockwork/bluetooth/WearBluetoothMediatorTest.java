package com.android.clockwork.bluetooth;

import static com.android.clockwork.bluetooth.WearBluetoothConstants.PROXY_SCORE_CLASSIC;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import com.android.clockwork.WearRobolectricTestRunner;
import com.android.clockwork.common.EventHistory;
import com.android.clockwork.common.PowerTracker;
import com.android.clockwork.common.TimeOnlyMode;
import com.android.internal.util.IndentingPrintWriter;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;

/** Test for {@link WearBluetoothMediator} */
@RunWith(WearRobolectricTestRunner.class)
@Config(manifest = Config.NONE,
        sdk = 26)
public class WearBluetoothMediatorTest {
    private static final String REASON = "";

    private final ShadowApplication shadowApplication = ShadowApplication.getInstance();

    @Captor ArgumentCaptor<Message> msgCaptor;
    @Mock AlarmManager mockAlarmManager;
    @Mock BluetoothAdapter mockBtAdapter;
    @Mock BluetoothClass mockPeripheralBluetoothClass;
    @Mock BluetoothClass mockPhoneBluetoothClass;
    @Mock BluetoothDevice mockBtPeripheral;
    @Mock BluetoothDevice mockBtPhone;
    @Mock BluetoothLogger mockBtLogger;
    @Mock BluetoothShardRunner mockShardRunner;
    @Mock CompanionTracker mockCompanionTracker;
    @Mock ContentResolver mockResolver;
    @Mock Handler mockHandler;
    @Mock IndentingPrintWriter mockIndentingPrintWriter;
    @Mock PowerTracker mockPowerTracker;
    @Mock TimeOnlyMode mockTimeOnlyMode;

    private Context mContext;
    private ContentResolver mContentResolver;
    private WearBluetoothMediator mMediator;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = shadowApplication.getApplicationContext();
        mContentResolver = RuntimeEnvironment.application.getContentResolver();

        when(mockBtPhone.getType()).thenReturn(BluetoothDevice.DEVICE_TYPE_CLASSIC);
        when(mockBtPhone.getAddress()).thenReturn("AA:BB:CC:DD:EE:FF");
        when(mockBtPhone.getBluetoothClass()).thenReturn(mockPhoneBluetoothClass);

        when(mockBtPeripheral.getType()).thenReturn(BluetoothDevice.DEVICE_TYPE_CLASSIC);
        when(mockBtPeripheral.getAddress()).thenReturn("12:34:56:78:90:12");
        when(mockBtPeripheral.getBluetoothClass()).thenReturn(mockPeripheralBluetoothClass);

        when(mockPhoneBluetoothClass.getMajorDeviceClass())
                .thenReturn(BluetoothClass.Device.Major.PHONE);
        when(mockPeripheralBluetoothClass.getMajorDeviceClass())
                .thenReturn(BluetoothClass.Device.Major.PERIPHERAL);

        when(mockBtAdapter.isEnabled()).thenReturn(true);
        when(mockCompanionTracker.getCompanion()).thenReturn(mockBtPhone);
        when(mockPowerTracker.isCharging()).thenReturn(false);

        mMediator = new WearBluetoothMediator(
                mContext,
                mockAlarmManager,
                mockBtAdapter,
                mockBtLogger,
                mockShardRunner,
                mockCompanionTracker,
                mockPowerTracker,
                mockTimeOnlyMode);
    }

    @Test
    public void testConstructorAndOnBootCompleted() {
        verify(mockCompanionTracker).addListener(mMediator);
        verify(mockPowerTracker).addListener(mMediator);

        mMediator.onBootCompleted();

        Assert.assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(BluetoothDevice.ACTION_ACL_CONNECTED)));
        Assert.assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED)));
        Assert.assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)));
        Assert.assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)));
    }

    @Test
    public void testOnBootCompletedWhenAdapterEnabled() {
        mMediator.onBootCompleted();
        verify(mockCompanionTracker).onBluetoothAdapterReady();
        verify(mockShardRunner).startHfcShard();
        verify(mockShardRunner).startProxyShard(PROXY_SCORE_CLASSIC, mMediator, "First Boot");
        verify(mockAlarmManager).set(eq(AlarmManager.ELAPSED_REALTIME), anyLong(),
                eq(mMediator.cancelConnectOnBootIntent));
    }

    @Test
    public void testOnBootCompletedWhenAdapterDisabled() {
        when(mockBtAdapter.isEnabled()).thenReturn(false);
        mMediator.onBootCompleted();
        verify(mockBtAdapter).enable();

        verifyNoMoreInteractions(mockShardRunner);
        verify(mockCompanionTracker, never()).onBluetoothAdapterReady();
        verify(mockAlarmManager, never()).set(eq(AlarmManager.ELAPSED_REALTIME), anyLong(),
                eq(mMediator.cancelConnectOnBootIntent));
    }

    @Test
    public void testOnBootCancelled() {
        mMediator.onBootCompleted();
        Intent btOnIntent = new Intent(WearBluetoothMediator.ACTION_CANCEL_ON_BOOT_CONNECT);
        mContext.sendBroadcast(btOnIntent);
        verify(mockShardRunner).stopProxyShard();
    }

    @Test
    public void testAdapterEnabledWithoutPairedDeviceDoesNotStartShards() {
        when(mockCompanionTracker.getCompanion()).thenReturn(null);
        when(mockBtAdapter.isEnabled()).thenReturn(false);
        mMediator.onBootCompleted();
        reset(mockShardRunner, mockAlarmManager);

        Intent btOnIntent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        btOnIntent.putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON);
        mContext.sendBroadcast(btOnIntent);

        verify(mockCompanionTracker).onBluetoothAdapterReady();
        verifyNoMoreInteractions(mockShardRunner);
    }

    @Test
    public void testFirstAdapterEnableStartsBothShards() {
        when(mockBtAdapter.isEnabled()).thenReturn(false);
        mMediator.onBootCompleted();
        reset(mockShardRunner, mockAlarmManager);

        Intent btOnIntent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        btOnIntent.putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON);
        mContext.sendBroadcast(btOnIntent);

        verify(mockCompanionTracker).onBluetoothAdapterReady();

        verify(mockShardRunner).startHfcShard();
        verify(mockShardRunner).startProxyShard(PROXY_SCORE_CLASSIC, mMediator, "First Boot");
        verify(mockAlarmManager).set(eq(AlarmManager.ELAPSED_REALTIME), anyLong(),
                eq(mMediator.cancelConnectOnBootIntent));
        reset(mockCompanionTracker, mockShardRunner, mockAlarmManager);

        when(mockCompanionTracker.getCompanion()).thenReturn(mockBtPhone);

        // the second broadcast should only call startHfcShard and do nothing else
        mContext.sendBroadcast(btOnIntent);
        verify(mockShardRunner).startHfcShard();
        verifyNoMoreInteractions(mockShardRunner);
        verify(mockCompanionTracker, never()).onBluetoothAdapterReady();
        verify(mockAlarmManager, never()).set(anyInt(), anyLong(), any(PendingIntent.class));
    }

    @Test
    public void testAdapterDisableStopsBothShards() {
        mMediator.onBootCompleted();
        reset(mockShardRunner, mockAlarmManager);

        Intent btOffIntent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        btOffIntent.putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
        mContext.sendBroadcast(btOffIntent);

        verify(mockShardRunner).stopHfcShard();
        verify(mockShardRunner).stopProxyShard();
    }

    @Test
    public void testPairedWithBluetoothPhoneStartsShards() {
        mMediator.onBootCompleted();
        reset(mockShardRunner, mockAlarmManager);

        when(mockCompanionTracker.getCompanion()).thenReturn(mockBtPhone);
        mMediator.onCompanionChanged();

        verify(mockShardRunner).startHfcShard();
        verify(mockShardRunner).startProxyShard(PROXY_SCORE_CLASSIC, mMediator, "Companion Found");
    }

    @Test
    public void testAclEventsStartAndStopProxyShard() {
        mMediator.onBootCompleted();
        reset(mockShardRunner, mockAlarmManager);

        Intent aclConnected = new Intent(BluetoothDevice.ACTION_ACL_CONNECTED);
        aclConnected.putExtra(BluetoothDevice.EXTRA_DEVICE, mockBtPhone);

        mContext.sendBroadcast(aclConnected);
        verify(mockShardRunner).startProxyShard(PROXY_SCORE_CLASSIC, mMediator,
                "Companion Connected");

        reset(mockShardRunner);
        Intent aclDisconnected = new Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        aclDisconnected.putExtra(BluetoothDevice.EXTRA_DEVICE, mockBtPhone);
        mContext.sendBroadcast(aclDisconnected);
        verify(mockShardRunner).stopProxyShard();
    }

    @Test
    public void testAclEventsForNonCompanionDeviceDoNothing() {
        mMediator.onBootCompleted();
        reset(mockShardRunner, mockAlarmManager);

        Intent aclConnected = new Intent(BluetoothDevice.ACTION_ACL_CONNECTED);
        aclConnected.putExtra(BluetoothDevice.EXTRA_DEVICE, mockBtPeripheral);

        mContext.sendBroadcast(aclConnected);
        verify(mockShardRunner, never()).startProxyShard(PROXY_SCORE_CLASSIC, mMediator, REASON);

        reset(mockShardRunner);
        Intent aclDisconnected = new Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        aclDisconnected.putExtra(BluetoothDevice.EXTRA_DEVICE, mockBtPeripheral);
        mContext.sendBroadcast(aclDisconnected);
        verify(mockShardRunner, never()).stopProxyShard();
    }

    @Test
    public void testProxyConnectedCancelsReceivers() {
        mMediator.onBootCompleted();
        Assert.assertNotNull(mMediator.cancelConnectOnBootReceiver);
        Assert.assertNotNull(mMediator.cancelConnectOnBootIntent);
        verify(mockShardRunner).startProxyShard(PROXY_SCORE_CLASSIC, mMediator, "First Boot");
        verify(mockAlarmManager).set(eq(AlarmManager.ELAPSED_REALTIME), anyLong(),
                eq(mMediator.cancelConnectOnBootIntent));
        reset(mockAlarmManager, mockShardRunner);

        mMediator.onProxyConnectionChange(false, PROXY_SCORE_CLASSIC);
        Assert.assertNotNull(mMediator.cancelConnectOnBootReceiver);
        Assert.assertNotNull(mMediator.cancelConnectOnBootIntent);
        Assert.assertFalse(mMediator.isProxyConnected());
        verify(mockAlarmManager, never()).cancel(any(PendingIntent.class));
        reset(mockAlarmManager);

        PendingIntent cancelIntent = mMediator.cancelConnectOnBootIntent;

        mMediator.onProxyConnectionChange(true, PROXY_SCORE_CLASSIC);
        verify(mockAlarmManager).cancel(cancelIntent);
        Assert.assertNull(mMediator.cancelConnectOnBootReceiver);
        Assert.assertNull(mMediator.cancelConnectOnBootIntent);
        Assert.assertTrue(mMediator.isProxyConnected());

        reset(mockAlarmManager);
        mMediator.onProxyConnectionChange(false, PROXY_SCORE_CLASSIC);
        mMediator.onProxyConnectionChange(true, PROXY_SCORE_CLASSIC);
        Assert.assertNull(mMediator.cancelConnectOnBootReceiver);
        Assert.assertNull(mMediator.cancelConnectOnBootIntent);
        Assert.assertTrue(mMediator.isProxyConnected());
        verify(mockAlarmManager, never()).cancel(any(PendingIntent.class));
    }

    @Test
    public void testTimeOnlyModeDisablesBluetooth() {
        mMediator.onBootCompleted();
        // Replace handler with mock
        mMediator.mRadioPowerHandler = mockHandler;

        mMediator.onTimeOnlyModeChanged(true);
        verifyPowerChange(WearBluetoothMediator.MSG_DISABLE_BT,
                WearBluetoothMediator.Reason.OFF_TIME_ONLY_MODE);

        mMediator.onTimeOnlyModeChanged(false);
        verifyPowerChange(WearBluetoothMediator.MSG_ENABLE_BT,
                WearBluetoothMediator.Reason.ON_AUTO);
    }

    @Test
    public void testActivityModeDisablesBluetooth() {
        mMediator.onBootCompleted();
        // Replace handler with mock
        mMediator.mRadioPowerHandler = mockHandler;

        mMediator.updateActivityMode(true);
        verifyPowerChange(WearBluetoothMediator.MSG_DISABLE_BT,
                WearBluetoothMediator.Reason.OFF_ACTIVITY_MODE);

        mMediator.updateActivityMode(false);
        verifyPowerChange(WearBluetoothMediator.MSG_ENABLE_BT,
                WearBluetoothMediator.Reason.ON_AUTO);
    }

    @Test
    public void testLogCompanionPairing() {
        mMediator.onBootCompleted();
        when(mockCompanionTracker.isCompanionBle()).thenReturn(true);
        mMediator.onCompanionChanged();
        verify(mockBtLogger).logCompanionPairingEvent(true);
        reset(mockBtLogger);

        when(mockCompanionTracker.isCompanionBle()).thenReturn(false);
        mMediator.onCompanionChanged();
        verify(mockBtLogger).logCompanionPairingEvent(false);
    }

    @Test
    public void testLogProxyConnectionChanges() {
        mMediator.onBootCompleted();
        mMediator.onProxyConnectionChange(true, PROXY_SCORE_CLASSIC);
        verify(mockBtLogger).logProxyConnectionChange(true);
        reset(mockBtLogger);

        mMediator.onProxyConnectionChange(false, PROXY_SCORE_CLASSIC);
        verify(mockBtLogger).logProxyConnectionChange(false);
        reset(mockBtLogger);
    }

    @Test
    public void testLogUnexpectedPairing() {
        mMediator.onBootCompleted();
        Intent unexpectedBondEvent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        unexpectedBondEvent.putExtra(BluetoothDevice.EXTRA_DEVICE, mockBtPhone);
        unexpectedBondEvent.putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                BluetoothDevice.BOND_BONDED);
        unexpectedBondEvent.putExtra(BluetoothDevice.EXTRA_BOND_STATE,
                BluetoothDevice.BOND_BONDING);
        mContext.sendBroadcast(unexpectedBondEvent);
        verify(mockBtLogger).logUnexpectedPairingEvent(mockBtPhone);

        reset(mockBtLogger);
        Intent validBondEvent = new Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        validBondEvent.putExtra(BluetoothDevice.EXTRA_DEVICE, mockBtPhone);
        validBondEvent.putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                BluetoothDevice.BOND_NONE);
        validBondEvent.putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDING);
        mContext.sendBroadcast(validBondEvent);
        verify(mockBtLogger, never()).logUnexpectedPairingEvent(any(BluetoothDevice.class));
    }

    private void verifyPowerChange(int what, WearBluetoothMediator.Reason reason) {
        verify(mockHandler, atLeastOnce()).sendMessage(msgCaptor.capture());
        Assert.assertEquals(what, msgCaptor.getValue().what);
        Assert.assertEquals(reason, msgCaptor.getValue().obj);
    }

    @Test
    public void testBcastAcl_AclAlreadyConnected() {
        mMediator.onBootCompleted();

        verify(mockShardRunner).startProxyShard(PROXY_SCORE_CLASSIC, mMediator, "First Boot");
        // Simulate the callback from the mocked companion proxy shard
        mMediator.onProxyConnectionChange(true, PROXY_SCORE_CLASSIC);

        Intent aclConnected = new Intent(BluetoothDevice.ACTION_ACL_CONNECTED);
        aclConnected.putExtra(BluetoothDevice.EXTRA_DEVICE, mockBtPhone);
        mContext.sendBroadcast(aclConnected);

        verify(mockShardRunner).startProxyShard(PROXY_SCORE_CLASSIC, mMediator, "First Boot");
    }

    @Test
    public void testBcastAcl_ConnectedButNoCompanion() {
        mMediator.onBootCompleted();

        verify(mockShardRunner).startProxyShard(PROXY_SCORE_CLASSIC, mMediator, "First Boot");
        // Simulate the callback from the mocked companion proxy shard
        mMediator.onProxyConnectionChange(true, PROXY_SCORE_CLASSIC);

        when(mockCompanionTracker.getCompanion()).thenReturn(null);

        Intent aclConnected = new Intent(BluetoothDevice.ACTION_ACL_CONNECTED);
        aclConnected.putExtra(BluetoothDevice.EXTRA_DEVICE, mockBtPhone);
        mContext.sendBroadcast(aclConnected);

        verify(mockShardRunner).startProxyShard(PROXY_SCORE_CLASSIC, mMediator, "First Boot");
    }

    @Test
    public void testBcastBluetoothStateChange_StateOn() {
        mMediator.onBootCompleted();

        Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON);
        mContext.sendBroadcast(intent);

        verify(mockShardRunner, times(2)).startHfcShard();
    }

    @Test
    public void testBcastBluetoothStateChange_StateOff() {
        mMediator.onBootCompleted();

        Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
        mContext.sendBroadcast(intent);

        verify(mockShardRunner, times(1)).stopHfcShard();
    }

    @Test
    public void testBcastBluetoothStateChange_StateUnknown() {
        mMediator.onBootCompleted();

        Intent intent = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, -1);
        mContext.sendBroadcast(intent);

        verify(mockShardRunner, times(1)).startHfcShard();
        verify(mockShardRunner, never()).stopHfcShard();
    }


    @Test
    public void testChargingStateChanged_IsCharging() {
        mMediator.onBootCompleted();

        when(mockPowerTracker.isCharging()).thenReturn(true);

        mMediator.onChargingStateChanged();

        verify(mockShardRunner).updateProxyShard(anyInt());
    }

    @Test
    public void testChargingStateChanged_onBattery() {
        mMediator.onBootCompleted();
        when(mockPowerTracker.isCharging()).thenReturn(false);

        mMediator.onChargingStateChanged();

        verify(mockShardRunner).updateProxyShard(anyInt());
    }

    @Test
    public void testRadioPowerHandler_EnableBluetooth() {
        Message msg = Message.obtain(mMediator.mRadioPowerHandler,
                WearBluetoothMediator.MSG_ENABLE_BT, WearBluetoothMediator.Reason.OFF_TIME_ONLY_MODE);
        mMediator.mRadioPowerHandler.sendMessage(msg);

        assertTrue(mMediator.mRadioPowerHandler.hasMessages(WearBluetoothMediator.MSG_ENABLE_BT));

        ShadowLooper shadowLooper = shadowOf(mMediator.mRadioPowerThread.getLooper());
        shadowLooper.runToEndOfTasks();

        verify(mockBtAdapter).enable();
    }

    @Test
    public void testRadioPowerHandler_DisableBluetooth() {
        Message msg = Message.obtain(mMediator.mRadioPowerHandler,
                WearBluetoothMediator.MSG_DISABLE_BT, WearBluetoothMediator.Reason.OFF_TIME_ONLY_MODE);
        mMediator.mRadioPowerHandler.sendMessage(msg);

        ShadowLooper shadowLooper = shadowOf(mMediator.mRadioPowerThread.getLooper());
        shadowLooper.runToEndOfTasks();

        verify(mockBtAdapter).disable();
    }

    @Test
    public void testOnBootCompleted_AclAlreadyConnected() {
        mMediator.onCompanionChanged();

        mMediator.onBootCompleted();

        verify(mockBtAdapter, never()).isEnabled();
    }

    @Test
    public void testOnBootCompleted_AirplaneModeOn() {
        Settings.Global.putInt(mContentResolver, Settings.Global.AIRPLANE_MODE_ON, 1);
        when(mockBtAdapter.isEnabled()).thenReturn(false);

        mMediator.onBootCompleted();

        verify(mockBtAdapter, never()).enable();
    }

    @Test
    public void testOnBootCompleted_SysProxyAclAlreadyConnected() {
        mMediator.onProxyConnectionChange(true, 0);

        mMediator.onBootCompleted();

        verify(mockBtAdapter, never()).isEnabled();
    }

    @Test
    public void testBtDecision() {
        WearBluetoothMediator.BtDecision btDecision
            = mMediator.new BtDecision(WearBluetoothMediator.Reason.OFF_TIME_ONLY_MODE, 123);
        assertEquals(WearBluetoothMediator.Reason.OFF_TIME_ONLY_MODE.name(), btDecision.getName());
        assertEquals(123, btDecision.getTimestampMs());

        WearBluetoothMediator.BtDecision btDecision1 =
            mMediator.new BtDecision(WearBluetoothMediator.Reason.OFF_TIME_ONLY_MODE, 123);
        WearBluetoothMediator.BtDecision btDecision2 =
            mMediator.new BtDecision(WearBluetoothMediator.Reason.ON_AUTO, 123);

        assertTrue(btDecision.isDuplicateOf(btDecision1));
        assertFalse(btDecision.isDuplicateOf(btDecision2));
    }

    @Test
    public void testProxyConnectionEvent() {
        WearBluetoothMediator.ProxyConnectionEvent event
            = mMediator.new ProxyConnectionEvent(true, 123, 111);
        assertEquals("CON", event.getName().substring(0, 3));
        assertEquals(123, event.getTimestampMs());
        assertEquals(111, event.score);

        WearBluetoothMediator.ProxyConnectionEvent disconnectEvent
            = mMediator.new ProxyConnectionEvent(false, 123, 111);
        assertEquals("DIS", disconnectEvent.getName().substring(0, 3));
        assertEquals(123, disconnectEvent.getTimestampMs());
        assertEquals(111, disconnectEvent.score);

        WearBluetoothMediator.ProxyConnectionEvent event1
            = mMediator.new ProxyConnectionEvent(true, 123, 111);
        WearBluetoothMediator.ProxyConnectionEvent event2
            = mMediator.new ProxyConnectionEvent(true, 456, 222);
        WearBluetoothMediator.ProxyConnectionEvent event3
            = mMediator.new ProxyConnectionEvent(false, 123, 111);

        assertTrue(event.isDuplicateOf(event1));
        assertFalse(event.isDuplicateOf(event2));
        assertFalse(event.isDuplicateOf(event3));
        assertFalse(event.isDuplicateOf(new BogusEvent()));
    }

    @Test
    public void testDump() {
        // Companion connected or not
        mMediator.dump(mockIndentingPrintWriter);

        when(mockCompanionTracker.getCompanion()).thenReturn(null);
        mMediator.dump(mockIndentingPrintWriter);
        when(mockCompanionTracker.getCompanion()).thenReturn(mockBtPhone);

        // Ble or Classic
        when(mockCompanionTracker.isCompanionBle()).thenReturn(true);
        mMediator.dump(mockIndentingPrintWriter);

        when(mockCompanionTracker.isCompanionBle()).thenReturn(false);
        mMediator.dump(mockIndentingPrintWriter);

        // Acl connected or not
        mMediator.onCompanionChanged();
        mMediator.dump(mockIndentingPrintWriter);

        Intent aclDisconnected = new Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        aclDisconnected.putExtra(BluetoothDevice.EXTRA_DEVICE, mockBtPhone);
        mContext.sendBroadcast(aclDisconnected);
        mMediator.dump(mockIndentingPrintWriter);

        // Proxy connected or not
        mMediator.onProxyConnectionChange(true, 0);
        mMediator.dump(mockIndentingPrintWriter);

        mMediator.onProxyConnectionChange(false, 0);
        when(mockCompanionTracker.isCompanionBle()).thenReturn(false);
    }

    class BogusEvent implements EventHistory.Event {
        @Override
        public String getName() {
            return "";
        }

        @Override
        public long getTimestampMs() {
            return 0;
        }

        @Override
        public boolean isDuplicateOf(EventHistory.Event event) {
            return false;
        }
    }
}
