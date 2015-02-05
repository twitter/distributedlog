package com.twitter.distributedlog.service;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.twitter.finagle.NoBrokersAvailableException;

import java.net.SocketAddress;

/**
 * Chain multiple routing services
 */
public class ChainRoutingService implements RoutingService {

    @Deprecated
    public static ChainRoutingService of(RoutingService...services) {
        return new ChainRoutingService(services);
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder implements RoutingService.Builder {

        private RoutingService.Builder[] _routingServiceBuilders;

        private Builder() {}

        public Builder routingServiceBuilders(RoutingService.Builder...builders) {
            this._routingServiceBuilders = builders;
            return this;
        }

        @Override
        public RoutingService build() {
            Preconditions.checkNotNull(_routingServiceBuilders, "No routing service builder provided.");
            RoutingService[] services = new RoutingService[_routingServiceBuilders.length];
            for (int i = 0; i < services.length; i++) {
                services[i] = _routingServiceBuilders[i].build();
            }
            return new ChainRoutingService(services);
        }
    }

    protected final RoutingService[] routingServices;

    private ChainRoutingService(RoutingService[] routingServices) {
        this.routingServices = routingServices;
    }

    @Override
    public void startService() {
        for (RoutingService service : routingServices) {
            service.startService();
        }
    }

    @Override
    public void stopService() {
        for (RoutingService service : routingServices) {
            service.stopService();
        }
    }

    @Override
    public RoutingService registerListener(RoutingListener listener) {
        for (RoutingService service : routingServices) {
            service.registerListener(listener);
        }
        return this;
    }

    @Override
    public RoutingService unregisterListener(RoutingListener listener) {
        for (RoutingService service : routingServices) {
            service.registerListener(listener);
        }
        return this;
    }

    @Override
    public SocketAddress getHost(String key, SocketAddress previousAddr)
            throws NoBrokersAvailableException {
        for (RoutingService service : routingServices) {
            try {
                SocketAddress addr = service.getHost(key, previousAddr);
                if (!Objects.equal(addr, previousAddr)) {
                    return addr;
                }
            } catch (NoBrokersAvailableException nbae) {
                // if there isn't broker available in current service, try next service.
            }
        }
        throw new NoBrokersAvailableException("No host found for " + key + ", previous : " + previousAddr);
    }

    @Override
    public void removeHost(SocketAddress address, Throwable reason) {
        for (RoutingService service : routingServices) {
            service.removeHost(address, reason);
        }
    }
}
