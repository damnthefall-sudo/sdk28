package com.android.clockwork.connectivity;

import android.app.AlarmManager;
import android.content.Intent;
import com.android.clockwork.bluetooth.WearBluetoothMediator;
import com.android.clockwork.cellular.WearCellularMediator;
import com.android.clockwork.common.ActivityModeTracker;
import com.android.clockwork.common.OffBodyRadioOffObserver;
import com.android.clockwork.common.OffBodyTracker;
import com.android.clockwork.common.PowerTracker;
import com.android.clockwork.wifi.WearWifiMediator;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = Config.NEWEST_SDK)
public class WearConnectivityControllerTest {
    final ShadowApplication shadowApplication = ShadowApplication.getInstance();

    @Mock AlarmManager mockAlarmManager;

    @Mock WearBluetoothMediator mockBtMediator;
    @Mock WearWifiMediator mockWifiMediator;
    @Mock WearCellularMediator mockCellMediator;

    @Mock WearProxyNetworkAgent mockProxyNetworkAgent;

    @Mock ActivityModeTracker mockActivityModeTracker;
    @Mock OffBodyTracker mockOffBodyTracker;
    @Mock PowerTracker mockPowerTracker;

    @Mock OffBodyRadioOffObserver mockOffBodyRadioOffObserver;

    WearConnectivityController mController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mController = new WearConnectivityController(
                shadowApplication.getApplicationContext(),
                mockAlarmManager,
                mockBtMediator,
                mockWifiMediator,
                mockCellMediator,
                mockProxyNetworkAgent,
                mockActivityModeTracker,
                mockOffBodyTracker,
                mockPowerTracker,
                mockOffBodyRadioOffObserver);

        verify(mockProxyNetworkAgent).addListener(mController);
        verify(mockActivityModeTracker).addListener(mController);
        verify(mockOffBodyTracker).addListener(mController);
        verify(mockOffBodyRadioOffObserver).addListener(mController);

        // initial controller state
        when(mockProxyNetworkAgent.isProxyConnected()).thenReturn(true);
        when(mockOffBodyRadioOffObserver.isOffBodyRadioOffEnabled()).thenReturn(true);
        when(mockOffBodyRadioOffObserver.getOffBodyRadiosOffDelay()).thenReturn(0L);
        mController.onBootCompleted();
    }

    @Test
    public void testOnBootCompleted() {
        Assert.assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(WearConnectivityController.ACTION_PROXY_STATUS_CHANGE)));

        verify(mockWifiMediator).onBootCompleted(true);
        verify(mockCellMediator).onBootCompleted(true);
        verify(mockOffBodyTracker).register(shadowApplication.getApplicationContext());
        verify(mockOffBodyRadioOffObserver).register();
    }

    @Test
    public void testProxyConnectionStateForwardingWithDelay() {
        reset(mockAlarmManager, mockWifiMediator, mockCellMediator);

        when(mockProxyNetworkAgent.isProxyConnected()).thenReturn(false);
        mController.onProxyConnectionChange(false);
        verify(mockAlarmManager).cancel(mController.notifyProxyStatusChangeIntent);
        verify(mockAlarmManager).setWindow(
                anyInt(), anyLong(), anyLong(), eq(mController.notifyProxyStatusChangeIntent));
        verifyNoMoreInteractions(mockWifiMediator);
        verifyNoMoreInteractions(mockCellMediator);

        reset(mockAlarmManager, mockWifiMediator, mockCellMediator);

        when(mockProxyNetworkAgent.isProxyConnected()).thenReturn(true);
        mController.onProxyConnectionChange(true);
        verify(mockAlarmManager).cancel(mController.notifyProxyStatusChangeIntent);
        verifyNoMoreInteractions(mockAlarmManager);
        verify(mockWifiMediator).updateProxyConnected(true);
        verify(mockCellMediator).updateProxyConnected(true);
    }

    @Test
    public void testProxyConnectionStateForwardingWithoutDelay() {
        mController.setBluetoothStateChangeDelay(0);

        when(mockProxyNetworkAgent.isProxyConnected()).thenReturn(false);
        mController.onProxyConnectionChange(false);
        verify(mockWifiMediator).updateProxyConnected(false);
        verify(mockCellMediator).updateProxyConnected(false);

        reset(mockWifiMediator, mockCellMediator);

        when(mockProxyNetworkAgent.isProxyConnected()).thenReturn(true);
        mController.onProxyConnectionChange(true);
        verify(mockWifiMediator).updateProxyConnected(true);
        verify(mockCellMediator).updateProxyConnected(true);
    }

    @Test
    public void testNetworkRequestForwarding() {
        mController.onWifiRequestsChanged(1);
        mController.onCellularRequestsChanged(2);
        mController.onHighBandwidthRequestsChanged(3);
        mController.onUnmeteredRequestsChanged(4);

        verify(mockWifiMediator).updateNumWifiRequests(1);
        verify(mockWifiMediator).updateNumHighBandwidthRequests(3);
        verify(mockWifiMediator).updateNumUnmeteredRequests(4);

        verify(mockCellMediator).updateNumCellularRequests(2);
        verify(mockCellMediator).updateNumHighBandwidthRequests(3);
    }

    @Test
    public void testActivityModeChanges() {
        reset(mockWifiMediator, mockCellMediator, mockBtMediator);
        when(mockActivityModeTracker.affectsBluetooth()).thenReturn(false);
        when(mockActivityModeTracker.affectsWifi()).thenReturn(false);
        when(mockActivityModeTracker.affectsCellular()).thenReturn(false);

        mController.onActivityModeChanged(true);
        verifyNoMoreInteractions(mockWifiMediator);
        verifyNoMoreInteractions(mockCellMediator);
        verifyNoMoreInteractions(mockBtMediator);

        // TODO set up various radio matrix/configurations and test that they get toggled
    }

    @Test
    public void testOffBodyChanges() {
        mController.onOffBodyChanged(true);

        verify(mockAlarmManager).setWindow(
                anyInt(), anyLong(), anyLong(), eq(mController.notifyOffBodyChangeIntent));

        reset(mockWifiMediator, mockCellMediator, mockBtMediator, mockAlarmManager);

        mController.onOffBodyChanged(false);

        verify(mockAlarmManager).cancel(mController.notifyOffBodyChangeIntent);

        verify(mockBtMediator).updateOffBodyState(false);
        verify(mockWifiMediator).updateOffBodyState(false);
        verify(mockCellMediator).updateOffBodyState(false);
    }
}
