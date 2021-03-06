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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import android.util.FloatProperty;
import android.util.Property;
import android.view.View;

import com.android.systemui.statusbar.stack.AnimationProperties;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An animatable property of a view. Used with {@link PropertyAnimator}
 */
public interface AnimatableProperty {
    int getAnimationStartTag();

    int getAnimationEndTag();

    int getAnimatorTag();

    Property getProperty();

    static <T extends View> AnimatableProperty from(String name, BiConsumer<T, Float> setter,
            Function<T, Float> getter, int animatorTag, int startValueTag, int endValueTag) {
        Property<T, Float> property = new FloatProperty<T>(name) {

            @Override
            public Float get(T object) {
                return getter.apply(object);
            }

            @Override
            public void setValue(T object, float value) {
                setter.accept(object, value);
            }
        };
        return new AnimatableProperty() {
            @Override
            public int getAnimationStartTag() {
                return startValueTag;
            }

            @Override
            public int getAnimationEndTag() {
                return endValueTag;
            }

            @Override
            public int getAnimatorTag() {
                return animatorTag;
            }

            @Override
            public Property getProperty() {
                return property;
            }
        };
    }
}
