/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.transition;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

import android.support.test.filters.MediumTest;
import android.view.View;

import androidx.transition.test.R;

import org.junit.Before;
import org.junit.Test;

@MediumTest
public class TransitionSetTest extends BaseTest {

    private final TransitionSet mTransitionSet = new TransitionSet();
    private final Transition mTransition = new TransitionTest.EmptyTransition();

    @Before
    public void setUp() {
        // mTransitionSet has 1 item from the start
        mTransitionSet.addTransition(mTransition);
    }

    @Test
    public void testOrdering() {
        assertThat(mTransitionSet.getOrdering(), is(TransitionSet.ORDERING_TOGETHER));
        assertThat(mTransitionSet.setOrdering(TransitionSet.ORDERING_SEQUENTIAL),
                is(sameInstance(mTransitionSet)));
        assertThat(mTransitionSet.getOrdering(), is(TransitionSet.ORDERING_SEQUENTIAL));
    }

    @Test
    public void testAddAndRemoveTransition() {
        assertThat(mTransitionSet.getTransitionCount(), is(1));
        assertThat(mTransitionSet.getTransitionAt(0), is(sameInstance(mTransition)));
        Transition anotherTransition = new TransitionTest.EmptyTransition();
        assertThat(mTransitionSet.addTransition(anotherTransition),
                is(sameInstance(mTransitionSet)));
        assertThat(mTransitionSet.getTransitionCount(), is(2));
        assertThat(mTransitionSet.getTransitionAt(0), is(sameInstance(mTransition)));
        assertThat(mTransitionSet.getTransitionAt(1), is(sameInstance(anotherTransition)));
        assertThat(mTransitionSet.removeTransition(mTransition),
                is(sameInstance(mTransitionSet)));
        assertThat(mTransitionSet.getTransitionCount(), is(1));
    }

    @Test
    public void testSetDuration() {
        assertThat(mTransitionSet.setDuration(123), is(sameInstance(mTransitionSet)));
        assertThat(mTransitionSet.getDuration(), is(123L));
        assertThat(mTransition.getDuration(), is(123L));
    }

    @Test
    public void testTargetId() {
        assertThat(mTransitionSet.addTarget(R.id.view0), is(sameInstance(mTransitionSet)));
        assertThat(mTransitionSet.getTargetIds(), hasItem(R.id.view0));
        assertThat(mTransitionSet.getTargetIds(), hasSize(1));
        assertThat(mTransition.getTargetIds(), hasItem(R.id.view0));
        assertThat(mTransition.getTargetIds(), hasSize(1));
        assertThat(mTransitionSet.removeTarget(R.id.view0), is(sameInstance(mTransitionSet)));
        assertThat(mTransitionSet.getTargetIds(), hasSize(0));
        assertThat(mTransition.getTargetIds(), hasSize(0));
    }

    @Test
    public void testTargetView() {
        final View view = new View(rule.getActivity());
        assertThat(mTransitionSet.addTarget(view), is(sameInstance(mTransitionSet)));
        assertThat(mTransitionSet.getTargets(), hasItem(view));
        assertThat(mTransitionSet.getTargets(), hasSize(1));
        assertThat(mTransition.getTargets(), hasItem(view));
        assertThat(mTransition.getTargets(), hasSize(1));
        assertThat(mTransitionSet.removeTarget(view), is(sameInstance(mTransitionSet)));
        assertThat(mTransitionSet.getTargets(), hasSize(0));
        assertThat(mTransition.getTargets(), hasSize(0));
    }

    @Test
    public void testTargetName() {
        assertThat(mTransitionSet.addTarget("abc"), is(sameInstance(mTransitionSet)));
        assertThat(mTransitionSet.getTargetNames(), hasItem("abc"));
        assertThat(mTransitionSet.getTargetNames(), hasSize(1));
        assertThat(mTransition.getTargetNames(), hasItem("abc"));
        assertThat(mTransition.getTargetNames(), hasSize(1));
        assertThat(mTransitionSet.removeTarget("abc"), is(sameInstance(mTransitionSet)));
        assertThat(mTransitionSet.getTargetNames(), hasSize(0));
        assertThat(mTransition.getTargetNames(), hasSize(0));
    }

    @Test
    public void testTargetClass() {
        assertThat(mTransitionSet.addTarget(View.class), is(sameInstance(mTransitionSet)));
        assertThat(mTransitionSet.getTargetTypes(), hasItem(View.class));
        assertThat(mTransitionSet.getTargetTypes(), hasSize(1));
        assertThat(mTransition.getTargetTypes(), hasItem(View.class));
        assertThat(mTransition.getTargetTypes(), hasSize(1));
        assertThat(mTransitionSet.removeTarget(View.class), is(sameInstance(mTransitionSet)));
        assertThat(mTransitionSet.getTargetTypes(), hasSize(0));
        assertThat(mTransition.getTargetTypes(), hasSize(0));
    }

    @Test
    public void testSetPropagation() {
        final TransitionPropagation propagation = new SidePropagation();
        mTransitionSet.setPropagation(propagation);
        assertThat(mTransitionSet.getPropagation(), is(propagation));
        assertThat(mTransition.getPropagation(), is(propagation));
    }

}
