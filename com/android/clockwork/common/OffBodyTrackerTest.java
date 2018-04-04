package com.android.clockwork.common;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 23)
public class OffBodyTrackerTest {
    private static final boolean LISTENER_ON_BODY = false;
    private static final boolean LISTENER_OFF_BODY = true;
    private static final boolean INTENT_ON_BODY = true;
    private static final boolean INTENT_OFF_BODY = false;

    final ShadowApplication shadowApplication = ShadowApplication.getInstance();

    @Mock OffBodyTracker.Listener mockListener;
    OffBodyTracker offBodyTracker;

    BroadcastReceiver broadcastReceiver;
    Context context;

    private long clock = 20180228;

    @Before
    public void setUp() {
        context = shadowApplication.getApplicationContext();
        MockitoAnnotations.initMocks(this);

        offBodyTracker = new OffBodyTracker();
        offBodyTracker.addListener(mockListener);
        offBodyTracker.register(context);

        broadcastReceiver = offBodyTracker.mOffBodyReceiver;
    }

    @Test
    public void testRegisterReceivers() {
      assertEquals("Expected 1 broadcast receiver for ACTION_DEVICE_ON_BODY_RECOGNITION", 1,
          shadowApplication.getReceiversForIntent(new Intent(
              OffBodyTracker.ACTION_DEVICE_ON_BODY_RECOGNITION)).size());
        assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(Intent.ACTION_SCREEN_OFF)));
        assertTrue(shadowApplication.hasReceiverForIntent(
                new Intent(Intent.ACTION_SCREEN_ON)));
    }

    @Test
    public void testStartsOnBody() {
        assertFalse("Tracker should start off body", offBodyTracker.isOffBody());
    }

    @Test
    public void testScreenDoesntMatterOnBody() {
        offBodyTracker
                .setIsOffBody(false)
                .setIsScreenOff(false);
        assertFalse(offBodyTracker.isOffBody());

        broadcastReceiver.onReceive(context, new Intent(Intent.ACTION_SCREEN_OFF));
        assertFalse(offBodyTracker.isOffBody());

        broadcastReceiver.onReceive(context, new Intent(Intent.ACTION_SCREEN_ON));
        assertFalse(offBodyTracker.isOffBody());

        verifyNoMoreInteractions(mockListener);
    }

    @Test
    public void testScreenChangesOffBody() {
        offBodyTracker
                .setIsOffBody(true)
                .setIsScreenOff(false);
        assertFalse(offBodyTracker.isOffBody());

        broadcastReceiver.onReceive(context, new Intent(Intent.ACTION_SCREEN_OFF));
        assertTrue(offBodyTracker.isOffBody());
        verify(mockListener).onOffBodyChanged(LISTENER_OFF_BODY);

        reset(mockListener);

        broadcastReceiver.onReceive(context, new Intent(Intent.ACTION_SCREEN_ON));
        assertFalse(offBodyTracker.isOffBody());
        verify(mockListener).onOffBodyChanged(LISTENER_ON_BODY);
    }

    @Test
    public void testOffBodyDoesntMatterScreenOn() {
        offBodyTracker
                .setIsOffBody(false)
                .setIsScreenOff(false);
        assertFalse(offBodyTracker.isOffBody());

        broadcastReceiver.onReceive(context, getOnBodyIntent(INTENT_OFF_BODY));
        assertFalse(offBodyTracker.isOffBody());

        broadcastReceiver.onReceive(context, getOnBodyIntent(INTENT_ON_BODY));
        assertFalse(offBodyTracker.isOffBody());

        verifyNoMoreInteractions(mockListener);
    }

    @Test
    public void testOffBodyChangesScreenOff() {
        offBodyTracker
                .setIsOffBody(false)
                .setIsScreenOff(true);
        assertFalse(offBodyTracker.isOffBody());

        broadcastReceiver.onReceive(context, getOnBodyIntent(INTENT_OFF_BODY));
        assertTrue(offBodyTracker.isOffBody());
        verify(mockListener).onOffBodyChanged(LISTENER_OFF_BODY);

        reset(mockListener);

        broadcastReceiver.onReceive(context, getOnBodyIntent(INTENT_ON_BODY));
        assertFalse(offBodyTracker.isOffBody());
        verify(mockListener).onOffBodyChanged(LISTENER_ON_BODY);

    }

    private Intent getOnBodyIntent(boolean isOnBody) {
        Intent intent = new Intent(OffBodyTracker.ACTION_DEVICE_ON_BODY_RECOGNITION);
        intent.putExtra(OffBodyTracker.EXTRA_DEVICE_ON_BODY_RECOGNITION, isOnBody);
        intent.putExtra(OffBodyTracker.EXTRA_LAST_CHANGED_TIME, advanceClock());
        return intent;
    }

    private long advanceClock() {
        clock += 1435;
        return clock;
    }
}
