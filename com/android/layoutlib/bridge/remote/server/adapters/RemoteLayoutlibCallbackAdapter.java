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

package com.android.layoutlib.bridge.remote.server.adapters;

import com.android.ide.common.rendering.api.ActionBarCallback;
import com.android.ide.common.rendering.api.AdapterBinding;
import com.android.ide.common.rendering.api.ILayoutPullParser;
import com.android.ide.common.rendering.api.LayoutlibCallback;
import com.android.ide.common.rendering.api.ParserFactory;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.SessionParams.Key;
import com.android.layout.remote.api.RemoteLayoutlibCallback;
import com.android.layout.remote.api.RemoteLayoutlibCallback.RemoteResolveResult;
import com.android.resources.ResourceType;
import com.android.tools.layoutlib.annotations.NotNull;
import com.android.util.Pair;

import org.xmlpull.v1.XmlPullParser;

import java.rmi.RemoteException;

public class RemoteLayoutlibCallbackAdapter extends LayoutlibCallback {
    private final RemoteLayoutlibCallback mDelegate;

    public RemoteLayoutlibCallbackAdapter(@NotNull RemoteLayoutlibCallback remote) {
        mDelegate = remote;
    }

    @Override
    public Object loadView(String name, Class[] constructorSignature, Object[] constructorArgs)
            throws Exception {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public String getNamespace() {
        try {
            return mDelegate.getNamespace();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Pair<ResourceType, String> resolveResourceId(int id) {
        try {
            RemoteResolveResult result = mDelegate.resolveResourceId(id);
            return result != null ? result.asPair() : null;
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String resolveResourceId(int[] id) {
        try {
            return mDelegate.resolveResourceId(id);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Integer getResourceId(ResourceType type, String name) {
        try {
            return mDelegate.getResourceId(type, name);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ILayoutPullParser getParser(String layoutName) {
        return null;
    }

    @Override
    public ILayoutPullParser getParser(ResourceValue layoutResource) {
        try {
            return new RemoteILayoutPullParserAdapter(mDelegate.getParser(layoutResource));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Object getAdapterItemValue(ResourceReference adapterView, Object adapterCookie,
            ResourceReference itemRef, int fullPosition, int positionPerType,
            int fullParentPosition, int parentPositionPerType, ResourceReference viewRef,
            ViewAttribute viewAttribute, Object defaultValue) {
        return null;
    }

    @Override
    public AdapterBinding getAdapterBinding(ResourceReference adapterViewRef, Object adapterCookie,
            Object viewObject) {
        return null;
    }

    @Override
    public ActionBarCallback getActionBarCallback() {
        try {
            return new RemoteActionBarCallbackAdapter(mDelegate.getActionBarCallback());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Object loadClass(String name, Class[] constructorSignature, Object[] constructorArgs)
            throws ClassNotFoundException {
        return super.loadClass(name, constructorSignature, constructorArgs);
    }

    @Override
    public boolean supports(int ideFeature) {
        try {
            return mDelegate.supports(ideFeature);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T getFlag(Key<T> key) {
        return super.getFlag(key);
    }

    @Override
    public ParserFactory getParserFactory() {
        try {
            return new RemoteParserFactoryAdapter(mDelegate.getParserFactory());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public XmlPullParser getXmlFileParser(String fileName) {
        try {
            return new RemoteXmlPullParserAdapter(mDelegate.getXmlFileParser(fileName));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
}
