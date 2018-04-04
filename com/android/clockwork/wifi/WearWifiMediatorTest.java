package com.android.clockwork.wifi;

import android.app.AlarmManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

import com.android.clockwork.bluetooth.CompanionTracker;
import com.android.clockwork.common.PowerTracker;

import org.junit.Assert;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.Test;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowApplication;

import java.util.ArrayList;
import java.util.Iterator;

import static com.android.clockwork.wifi.WearWifiMediatorSettings.WIFI_SETTING_OFF;
import static com.android.clockwork.wifi.WearWifiMediatorSettings.WIFI_SETTING_ON;

import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 23)
public class WearWifiMediatorTest {
    final ShadowApplication shadowApplication = ShadowApplication.getInstance();

    @Mock AlarmManager mockAlarmManager;
    @Mock WearWifiMediatorSettings mockWifiSettings;
    @Mock CompanionTracker mockCompanionTracker;
    @Mock PowerTracker mockPowerTracker;
    @Mock WifiBackoff mockWifiBackoff;
    @Mock WifiLogger mockWifiLogger;

    @Mock WifiManager mockWifiMgr;
    @Mock NetworkInfo mockWifiNetworkInfo;

    @Mock WifiConfiguration mockWifiConfiguration;

    WearWifiMediator mWifiMediator;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mWifiMediator = new WearWifiMediator(
                shadowApplication.getApplicationContext(),
                mockAlarmManager,
                mockWifiSettings,
                mockCompanionTracker,
                mockPowerTracker,
                mockWifiBackoff,
                mockWifiMgr,
                mockWifiLogger);
        // disable wifi lingering to allow easier testing of when mediator should turn wifi off
        mWifiMediator.setWifiLingerDuration(-999);

        // boot into default the setup, off charger, proxy connected
        bootWifiMediator();
    }

    /**
     * The default setup all tests begin with is the most common starting setup for most devices:
     * -- WiFi Setting is ON (Automatic)
     * -- Enable WiFi while Charging is ON
     * -- WiFi Adapter is DISABLED
     * -- Not in Wifi Backoff
     * -- One saved WiFi network. (this is non-standard but currently simplifies test cases)
     *
     * All test cases in this class assume this initial setup.
     */
    private void bootWifiMediator() {
        // default WiFi Settings values for every test
        when(mockWifiSettings.getIsInAirplaneMode()).thenReturn(false);
        when(mockWifiSettings.getWifiSetting()).thenReturn(WIFI_SETTING_ON);
        when(mockWifiSettings.getEnableWifiWhileCharging()).thenReturn(true);
        when(mockWifiSettings.getDisableWifiMediator()).thenReturn(false);
        when(mockWifiSettings.getWifiOnWhenProxyDisconnected()).thenReturn(true);

        when(mockWifiBackoff.isInBackoff()).thenReturn(false);

        when(mockPowerTracker.isCharging()).thenReturn(false);
        when(mockPowerTracker.isInPowerSave()).thenReturn(false);

        // wifi is initially off, with 1 network configured
        when(mockWifiMgr.isWifiEnabled()).thenReturn(false);
        ArrayList<WifiConfiguration> mockWifiConfigs = new ArrayList<>();
        mockWifiConfigs.add(mockWifiConfiguration);
        when(mockWifiMgr.getConfiguredNetworks()).thenReturn(mockWifiConfigs);

        mWifiMediator.onBootCompleted(true);
    }

    private void verifySetWifiEnabled(boolean enable) {
        verify(mockWifiMgr).setWifiEnabled(enable);
        reset(mockWifiMgr);
    }

    @Test
    public void testConstructoRegistersAppropriateReceiversAndListeners() {
        verify(mockWifiSettings).addListener(mWifiMediator);
        verify(mockPowerTracker).addListener(mWifiMediator);

        IntentFilter intentFilter = mWifiMediator.getBroadcastReceiverIntentFilter();
        for (Iterator<String> it = intentFilter.actionsIterator(); it.hasNext(); ) {
            String action = it.next();
            Assert.assertTrue("BroadcastReceiver not registered for action: " + action,
                    shadowApplication.hasReceiverForIntent(new Intent(action)));
        }
    }

    @Test
    public void testNoWifiUpdatesBeforeOnBootCompleted() {
        // since the default setUp for tests calls onBootCompleted, reconstruct mWifiMediator here
        mWifiMediator = new WearWifiMediator(
                shadowApplication.getApplicationContext(),
                mockAlarmManager,
                mockWifiSettings,
                mockCompanionTracker,
                mockPowerTracker,
                mockWifiBackoff,
                mockWifiMgr,
                mockWifiLogger);
        // disable wifi lingering to allow easier testing of when mediator should turn wifi off
        mWifiMediator.setWifiLingerDuration(-999);

        // now trigger some broadcasts, listeners, etc.
        when(mockPowerTracker.isCharging()).thenReturn(true);
        mWifiMediator.onChargingStateChanged();

        mWifiMediator.updateNumWifiRequests(1);
        mWifiMediator.updateNumWifiRequests(0);

        when(mockPowerTracker.isCharging()).thenReturn(true);
        mWifiMediator.onChargingStateChanged();

        final Intent wifiOnIntent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        wifiOnIntent.putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED);
        shadowApplication.sendBroadcast(wifiOnIntent);

        // verify that setWifiEnabled is never called
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());
    }

    @Test
    public void testOnBootCompletedOffCharger() {
        Assert.assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(WearWifiMediator.ACTION_EXIT_WIFI_LINGER)));

        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());
    }

    @Test
    public void testOnBootCompletedOnCharger() {
        Assert.assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(WearWifiMediator.ACTION_EXIT_WIFI_LINGER)));

        when(mockPowerTracker.isCharging()).thenReturn(true);
        mWifiMediator.onBootCompleted(true);
        verifySetWifiEnabled(true);
    }

    @Test
    public void testOnBootCompletedProxyDisonnectedEnablesWifi() {
        Assert.assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(WearWifiMediator.ACTION_EXIT_WIFI_LINGER)));

        mWifiMediator.onBootCompleted(false);
        verifySetWifiEnabled(true);
    }

    @Test
    public void testOnBootCompletedProxyDisonnectedNotEnableWifi() {
        // when WIFI_ON_WHEN_PROXY_DISCONNECTED option is disabled
        // we expect not enabling wifi when proxy is disconnected
        when(mockWifiSettings.getWifiOnWhenProxyDisconnected()).thenReturn(false);

        mWifiMediator.onBootCompleted(false);
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());
    }

    @Test
    public void testUpdateProxyConnected() {
        mWifiMediator.updateProxyConnected(false);
        verifySetWifiEnabled(true);

        mWifiMediator.updateProxyConnected(true);
        verifySetWifiEnabled(false);

        // when WIFI_ON_WHEN_PROXY_DISCONNECTED option is disabled
        // we expect not enabling wifi when proxy is disconnected
        mWifiMediator.onWifiOnWhenProxyDisconnectedChanged(false);

        mWifiMediator.updateProxyConnected(false);
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());

        mWifiMediator.updateProxyConnected(true);
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());
    }

    @Test
    public void testUpdateActivityMode() {
        // disconnect proxy first to enable wifi
        mWifiMediator.updateProxyConnected(false);
        verifySetWifiEnabled(true);

        // now enter active mode
        mWifiMediator.updateActivityMode(true);
        verifySetWifiEnabled(false);

        // exiting active mode should cause wifi to re-enable (b/c proxy is disconnected)
        mWifiMediator.updateActivityMode(false);
        verifySetWifiEnabled(true);
    }

    @Test
    public void testUpdateNetworkRequests() {
        mWifiMediator.updateNumWifiRequests(1);
        verifySetWifiEnabled(true);

        mWifiMediator.updateNumWifiRequests(0);
        verifySetWifiEnabled(false);

        mWifiMediator.updateNumHighBandwidthRequests(1);
        verifySetWifiEnabled(true);

        mWifiMediator.updateNumHighBandwidthRequests(0);
        verifySetWifiEnabled(false);

        // onUnmeteredRequest behavior depends on whether we're in BLE mode or not
        when(mockCompanionTracker.isCompanionBle()).thenReturn(false);
        mWifiMediator.updateNumUnmeteredRequests(1);
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());

        mWifiMediator.updateNumUnmeteredRequests(0);
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());

        when(mockCompanionTracker.isCompanionBle()).thenReturn(true);
        mWifiMediator.updateNumUnmeteredRequests(1);
        verifySetWifiEnabled(true);

        mWifiMediator.updateNumUnmeteredRequests(0);
        verifySetWifiEnabled(false);
    }

    @Test
    public void testUpdateNetworkRequestsProxyDisonnectedNotEnableWifi() {
        // when WIFI_ON_WHEN_PROXY_DISCONNECTED option is disabled
        // we expect not enabling wifi when proxy is disconnected
        mWifiMediator.onWifiOnWhenProxyDisconnectedChanged(false);

        // When proxy disconnected, wifi should remain off
        mWifiMediator.updateProxyConnected(false);
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());

        // When network requests present, turn wifi on
        mWifiMediator.updateNumWifiRequests(1);
        verifySetWifiEnabled(true);

        mWifiMediator.updateNumWifiRequests(0);
        verifySetWifiEnabled(false);

        // onUnmeteredRequest behavior depends on whether we're in BLE mode or not
        when(mockCompanionTracker.isCompanionBle()).thenReturn(false);
        mWifiMediator.updateNumUnmeteredRequests(1);
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());

        mWifiMediator.updateNumUnmeteredRequests(0);
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());

        when(mockCompanionTracker.isCompanionBle()).thenReturn(true);
        mWifiMediator.updateNumUnmeteredRequests(1);
        verifySetWifiEnabled(true);

        mWifiMediator.updateNumUnmeteredRequests(0);
        verifySetWifiEnabled(false);
    }

    @Test
    public void testWifiLinger() {
        mWifiMediator.setWifiLingerDuration(5000L);

        mWifiMediator.updateNumWifiRequests(1);
        verifySetWifiEnabled(true);

        mWifiMediator.updateNumWifiRequests(0);
        verify(mockAlarmManager).setWindow(eq(AlarmManager.ELAPSED_REALTIME),
                anyLong(), anyLong(), eq(mWifiMediator.exitWifiLingerIntent));
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());

        reset(mockAlarmManager, mockWifiMgr);
        mWifiMediator.updateNumWifiRequests(1);
        verify(mockAlarmManager).cancel(mWifiMediator.exitWifiLingerIntent);
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());

        reset(mockAlarmManager, mockWifiMgr);
    }

    @Test
    public void testWifiLingerIntentHandling() {
        mWifiMediator.setWifiLingerDuration(5000L);

        // when wifi is off (and not lingering), if the alarm goes off, nothing should happen
        reset(mockWifiMgr);
        shadowApplication.getApplicationContext().sendBroadcast(
                new Intent(WearWifiMediator.ACTION_EXIT_WIFI_LINGER));
        verifyNoMoreInteractions(mockWifiMgr);

        // when wifi is on (and not lingering), if the alarm goes off, nothing should happen
        reset(mockWifiMgr);
        mWifiMediator.updateNumWifiRequests(1);
        verifySetWifiEnabled(true);
        shadowApplication.getApplicationContext().sendBroadcast(
                new Intent(WearWifiMediator.ACTION_EXIT_WIFI_LINGER));
        verifyNoMoreInteractions(mockWifiMgr);

        // when wifi is on and lingering, if the alarm goes off, wifi should get disabled
        reset(mockWifiMgr);
        mWifiMediator.updateNumWifiRequests(0);
        verifyNoMoreInteractions(mockWifiMgr);
        shadowApplication.getApplicationContext().sendBroadcast(
                new Intent(WearWifiMediator.ACTION_EXIT_WIFI_LINGER));
        verifySetWifiEnabled(false);
    }

    @Test
    public void testWifiSettingsChanges() {
        // enable WiFi lingering for this test, to ensure that setting WiFi to OFF will bypass it
        mWifiMediator.setWifiLingerDuration(5000L);
        // disconnect proxy to enable WiFi
        mWifiMediator.updateProxyConnected(false);
        verifySetWifiEnabled(true);

        // turning off WiFi should disable the adapter immediately
        mWifiMediator.onWifiSettingChanged(WIFI_SETTING_OFF);
        verifySetWifiEnabled(false);

        // plug in the power, get some network requests going, disconnect proxy, and
        // enter wifi settings -- all of these should result in no change to WiFi state
        shadowApplication.sendBroadcast(new Intent(Intent.ACTION_POWER_CONNECTED));
        mWifiMediator.updateProxyConnected(false);
        mWifiMediator.updateNumHighBandwidthRequests(3);
        mWifiMediator.updateNumUnmeteredRequests(3);
        mWifiMediator.updateNumWifiRequests(3);
        mWifiMediator.onInWifiSettingsMenuChanged(true);

        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());

        // now turn WiFi back ON, and the adapter should get enabled
        mWifiMediator.onWifiSettingChanged(WIFI_SETTING_ON);
        verifySetWifiEnabled(true);
    }

    @Test
    public void testInWifiSettingsBehavior() {
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());

        mWifiMediator.onInWifiSettingsMenuChanged(true);
        verifySetWifiEnabled(true);

        mWifiMediator.onInWifiSettingsMenuChanged(false);
        verifySetWifiEnabled(false);

        // if WiFi Setting is set to Off, then we don't turn WiFi on when in the menu
        mWifiMediator.onWifiSettingChanged(WIFI_SETTING_OFF);
        mWifiMediator.onInWifiSettingsMenuChanged(true);
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());
    }

    @Test
    public void testEnableWifiWhileCharging() {
        when(mockPowerTracker.isCharging()).thenReturn(true);
        mWifiMediator.onChargingStateChanged();
        verifySetWifiEnabled(true);

        when(mockPowerTracker.isCharging()).thenReturn(false);
        mWifiMediator.onChargingStateChanged();
        verifySetWifiEnabled(false);

        mWifiMediator.onEnableWifiWhileChargingChanged(false);

        when(mockPowerTracker.isCharging()).thenReturn(true);
        mWifiMediator.onChargingStateChanged();
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());

        when(mockPowerTracker.isCharging()).thenReturn(false);
        mWifiMediator.onChargingStateChanged();
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());
    }

    @Test
    public void testEnableWifiWhileChargingProxyDisonnectedNotEnableWifi() {
        // when WIFI_ON_WHEN_PROXY_DISCONNECTED option is disabled
        // we expect not enabling wifi when proxy is disconnected
        mWifiMediator.onWifiOnWhenProxyDisconnectedChanged(false);

        // When proxy disconnected, wifi should remain off
        mWifiMediator.updateProxyConnected(false);
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());

        when(mockPowerTracker.isCharging()).thenReturn(true);
        mWifiMediator.onChargingStateChanged();
        verifySetWifiEnabled(true);

        when(mockPowerTracker.isCharging()).thenReturn(false);
        mWifiMediator.onChargingStateChanged();
        verifySetWifiEnabled(false);

        mWifiMediator.onEnableWifiWhileChargingChanged(false);

        when(mockPowerTracker.isCharging()).thenReturn(true);
        mWifiMediator.onChargingStateChanged();
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());

        when(mockPowerTracker.isCharging()).thenReturn(false);
        mWifiMediator.onChargingStateChanged();
        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());
    }

    @Test
    public void testPowerSaveMode() {
        mWifiMediator.updateProxyConnected(false);
        verifySetWifiEnabled(true);

        when(mockPowerTracker.isInPowerSave()).thenReturn(true);
        mWifiMediator.onPowerSaveModeChanged();
        verifySetWifiEnabled(false);

        when(mockPowerTracker.isInPowerSave()).thenReturn(false);
        mWifiMediator.onPowerSaveModeChanged();
        verifySetWifiEnabled(true);
    }

    @Test
    public void testHardwareLowPowerMode() {
        // enable WiFi lingering for this test, to ensure that enabling HLPM bypasses it
        mWifiMediator.setWifiLingerDuration(5000L);
        // disconnect proxy to enable WiFi
        mWifiMediator.updateProxyConnected(false);
        verifySetWifiEnabled(true);

        // turning on HLPM should cause WiFi to go down immediately
        mWifiMediator.onHardwareLowPowerModeChanged(true);
        verifySetWifiEnabled(false);

        // plug in the power, get some network requests going, disconnect proxy, and
        // enter wifi settings -- all of these should result in no change to WiFi state
        shadowApplication.sendBroadcast(new Intent(Intent.ACTION_POWER_CONNECTED));
        mWifiMediator.updateProxyConnected(false);
        mWifiMediator.updateNumHighBandwidthRequests(3);
        mWifiMediator.updateNumUnmeteredRequests(3);
        mWifiMediator.updateNumWifiRequests(3);
        mWifiMediator.onInWifiSettingsMenuChanged(true);

        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());

        // now disable HLPM; WiFi should come on immediately
        mWifiMediator.onHardwareLowPowerModeChanged(false);
        verifySetWifiEnabled(true);
    }

    /**
     * Ensures that WifiMediator correctly monitors any changes to the WiFi Adapter state
     * and forces the adapter back to the correct state if a state change which is inconsistent
     * with the current WifiMediator decision is detected.
     */
    @Test
    public void testWifiMediatorTracksAdapterStateChanges() {
        final Intent wifiOnIntent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        wifiOnIntent.putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED);
        final Intent wifiOffIntent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        wifiOffIntent.putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLED);

        // WiFi Setting is ON; adapter should be OFF;  if we hear that WiFi got turned on,
        // WifiMediator should turn it back off
        shadowApplication.sendBroadcast(wifiOnIntent);
        verifySetWifiEnabled(false);

        // WiFi Setting is ON; adapter is ON; if we hear that WiFi got turned on,
        // WifiMediator should flip WiFi back on
        mWifiMediator.updateNumWifiRequests(5);
        mWifiMediator.updateProxyConnected(false);
        verifySetWifiEnabled(true);
        shadowApplication.sendBroadcast(wifiOffIntent);
        verifySetWifiEnabled(true);

        // WiFi Setting is OFF; adapter is OFF; if we hear that WiFi got turned on,
        // WifiMediator will now allow the adapter to stay on, but ensure that the WiFi Setting
        // is correctly toggled back to ON/AUTO
        mWifiMediator.onWifiSettingChanged(WIFI_SETTING_OFF);
        verifySetWifiEnabled(false);
        shadowApplication.sendBroadcast(wifiOnIntent);
        verify(mockWifiSettings).putWifiSetting(WIFI_SETTING_ON);
    }

    @Test
    public void testDisableWifiMediator() {
        mWifiMediator.onDisableWifiMediatorChanged(true);

        // plug in the power, get some network requests going, disconnect proxy, and
        // enter wifi settings -- all of these should result in no change to WiFi state
        shadowApplication.sendBroadcast(new Intent(Intent.ACTION_POWER_CONNECTED));
        mWifiMediator.updateProxyConnected(false);
        mWifiMediator.updateNumHighBandwidthRequests(3);
        mWifiMediator.updateNumUnmeteredRequests(3);
        mWifiMediator.updateNumWifiRequests(3);
        mWifiMediator.onInWifiSettingsMenuChanged(true);

        verify(mockWifiMgr, never()).setWifiEnabled(anyBoolean());
    }

    @Test
    public void testWifiBackoff() {
        mWifiMediator.updateProxyConnected(false);
        verifySetWifiEnabled(true);
        verify(mockWifiBackoff).scheduleBackoff();

        when(mockWifiBackoff.isInBackoff()).thenReturn(true);
        mWifiMediator.onWifiBackoffChanged();
        verifySetWifiEnabled(false);

        reset(mockWifiBackoff);
        when(mockWifiBackoff.isInBackoff()).thenReturn(false);
        mWifiMediator.onWifiBackoffChanged();
        verifySetWifiEnabled(true);
        verify(mockWifiBackoff).scheduleBackoff();

        reset(mockWifiBackoff);
        Intent i = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        i.putExtra(WifiManager.EXTRA_NETWORK_INFO, mockWifiNetworkInfo);
        when(mockWifiNetworkInfo.isConnected()).thenReturn(true);
        shadowApplication.sendBroadcast(i);
        verify(mockWifiBackoff).cancelBackoff();

        reset(mockWifiBackoff);
        when(mockWifiNetworkInfo.isConnected()).thenReturn(false);
        shadowApplication.sendBroadcast(i);
        verify(mockWifiBackoff).scheduleBackoff();
    }

    @Test
    public void testNoWifiNetworksConfigured() {
        mWifiMediator.setNumConfiguredNetworks(0);

        // Any changes to proxy connectivity should not cause WiFi to be enabled.
        mWifiMediator.updateProxyConnected(false);
        mWifiMediator.updateProxyConnected(true);
        mWifiMediator.updateProxyConnected(false);
        verify(mockWifiMgr, never()).setWifiEnabled(true);

        // But being in WiFi Settings, being on charger, or NetworkRequests
        // may allow WiFi to be brought up.
        mWifiMediator.onInWifiSettingsMenuChanged(true);
        verifySetWifiEnabled(true);

        mWifiMediator.onInWifiSettingsMenuChanged(false);
        verifySetWifiEnabled(false);

        when(mockPowerTracker.isCharging()).thenReturn(true);
        mWifiMediator.onChargingStateChanged();
        verifySetWifiEnabled(true);

        when(mockPowerTracker.isCharging()).thenReturn(false);
        mWifiMediator.onChargingStateChanged();
        verifySetWifiEnabled(false);

        mWifiMediator.updateNumWifiRequests(1);
        verifySetWifiEnabled(true);

        mWifiMediator.updateNumWifiRequests(0);
        verifySetWifiEnabled(false);
    }
}
