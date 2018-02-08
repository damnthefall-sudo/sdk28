/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.graphics;

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.ImageDecoder.InputStreamSource;
import android.graphics.ImageDecoder.OnHeaderDecodedListener;
import android.graphics.ImageDecoder.Source;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

import java.io.IOException;

public class ImageDecoder_Delegate {
    @LayoutlibDelegate
    static Bitmap decodeBitmap(@NonNull Source src, @Nullable OnHeaderDecodedListener listener)
            throws IOException {
        TypedValue value = new TypedValue();
        value.density = src.getDensity();
        return BitmapFactory.decodeResourceStream(src.getResources(), value,
                ((InputStreamSource) src).mInputStream, null, null);
    }

    @LayoutlibDelegate
    static Bitmap decodeBitmap(@NonNull Source src) throws IOException {
        return decodeBitmap(src, null);
    }

    @LayoutlibDelegate
    static Bitmap decodeBitmap(ImageDecoder thisDecoder) {
        return null;
    }

    @LayoutlibDelegate
    static Drawable decodeDrawable(@NonNull Source src, @Nullable OnHeaderDecodedListener listener)
            throws IOException {
        Bitmap bitmap = decodeBitmap(src, listener);
        return new BitmapDrawable(src.getResources(), bitmap);
    }

    @LayoutlibDelegate
    static Drawable decodeDrawable(@NonNull Source src) throws IOException {
        return decodeDrawable(src, null);
    }
}
