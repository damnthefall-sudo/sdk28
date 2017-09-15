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

package com.android.layoutlib.bridge.remote.server;

import com.android.layout.remote.api.RemoteBridge;
import com.android.tools.layoutlib.annotations.NotNull;

import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

/**
 * Main server class. The main method will start an RMI server for the {@link RemoteBridgeImpl}
 * class.
 */
public class ServerMain {
    public static int REGISTRY_BASE_PORT = 9000;

    private final int mPort;
    private final Registry mRegistry;

    private ServerMain(int port, @NotNull Registry registry) {
        mPort = port;
        mRegistry = registry;
    }

    public int getPort() {
        return mPort;
    }

    public void stop() {
        try {
            UnicastRemoteObject.unexportObject(mRegistry, true);
        } catch (NoSuchObjectException ignored) {
        }
    }

    /**
     * The server will start looking for ports available for the {@link Registry} until a free one
     * is found. The port number will be returned.
     * If no ports are available, a {@link RemoteException} will be thrown.
     * @param basePort port number to start looking for available ports
     * @param limit number of ports to check. The last port to be checked will be (basePort + limit)
     */
    public static ServerMain startServer(int basePort, int limit) throws RemoteException {
        RemoteBridgeImpl remoteBridge = new RemoteBridgeImpl();
        RemoteBridge stub = (RemoteBridge) UnicastRemoteObject.exportObject(remoteBridge, 0);

        RemoteException lastException = null;
        for (int port = basePort; port <= basePort + limit; port++) {
            try {
                Registry registry = LocateRegistry.createRegistry(port);
                registry.rebind(RemoteBridge.class.getName(), stub);
                return new ServerMain(port, registry);
            } catch (RemoteException e) {
                lastException = e;
            }
        }

        if (lastException == null) {
            lastException = new RemoteException("Unable to start server");
        }

        throw lastException;
    }

    public static void main(String[] args) throws RemoteException {
        ServerMain server = startServer(REGISTRY_BASE_PORT, 10);
        System.out.println("Server is running on port " + server.getPort());
    }
}
