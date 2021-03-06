/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.spotify.internal;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandlerFactory;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerFactory;
import org.eclipse.smarthome.io.net.http.HttpClientFactory;
import org.openhab.binding.spotify.internal.discovery.SpotifyDeviceDiscoveryService;
import org.openhab.binding.spotify.internal.handler.SpotifyBridgeHandler;
import org.openhab.binding.spotify.internal.handler.SpotifyDeviceHandler;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;

/**
 * The {@link SpotifyHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Andreas Stenlund - Initial contribution
 * @author Matthew Bowman - Initial contribution
 * @author Hilbrand Bouwkamp - Added registration of discovery service to binding to this class
 */
@NonNullByDefault
@Component(service = ThingHandlerFactory.class, immediate = true, configurationPid = "binding.spotify", configurationPolicy = ConfigurationPolicy.OPTIONAL)
public class SpotifyHandlerFactory extends BaseThingHandlerFactory {

    private final Map<ThingUID, ServiceRegistration<?>> discoveryServiceRegs = new HashMap<>();

    private @NonNullByDefault({}) SpotifyAuthService authService;
    private @NonNullByDefault({}) HttpClient httpClient;

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SpotifyBindingConstants.THING_TYPE_PLAYER.equals(thingTypeUID)
                || SpotifyBindingConstants.THING_TYPE_DEVICE.equals(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (SpotifyBindingConstants.THING_TYPE_PLAYER.equals(thingTypeUID)) {
            SpotifyBridgeHandler handler = new SpotifyBridgeHandler((Bridge) thing, httpClient);
            authService.addSpotifyAccountHandler(handler);
            registerDiscoveryService(handler);
            return handler;
        }
        if (SpotifyBindingConstants.THING_TYPE_DEVICE.equals(thingTypeUID)) {
            return new SpotifyDeviceHandler(thing);
        }
        return null;
    }

    /**
     * Registers Spotify Device discovery service to the bridge handler.
     *
     * @param handler handler to register service for
     */
    private synchronized void registerDiscoveryService(SpotifyBridgeHandler handler) {
        SpotifyDeviceDiscoveryService discoveryService = new SpotifyDeviceDiscoveryService(handler, httpClient);
        ServiceRegistration<?> serviceRegistration = bundleContext.registerService(DiscoveryService.class.getName(),
                discoveryService, new Hashtable<String, Object>());

        discoveryService.activate();
        discoveryServiceRegs.put(handler.getThing().getUID(), serviceRegistration);
    }

    @Override
    protected synchronized void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof SpotifyBridgeHandler) {
            ThingUID uid = thingHandler.getThing().getUID();
            ServiceRegistration<?> serviceReg = discoveryServiceRegs.get(uid);

            if (serviceReg != null) {
                SpotifyDeviceDiscoveryService service = (SpotifyDeviceDiscoveryService) getBundleContext()
                        .getService(serviceReg.getReference());
                // remove discovery service, if bridge handler is removed
                service.deactivate();
                serviceReg.unregister();
                discoveryServiceRegs.remove(uid);
                authService.removeSpotifyAccountHandler((SpotifyBridgeHandler) thingHandler);
            }
        }
    }

    @Reference
    protected void setHttpClientFactory(HttpClientFactory httpClientFactory) {
        this.httpClient = httpClientFactory.getCommonHttpClient();
    }

    protected void unsetHttpClientFactory(HttpClientFactory httpClientFactory) {
        this.httpClient = null;
    }

    @Reference
    protected void bindAuthService(SpotifyAuthService service) {
        this.authService = service;
    }

    protected void unbindAuthService(SpotifyAuthService service) {
        this.authService = null;
    }
}
