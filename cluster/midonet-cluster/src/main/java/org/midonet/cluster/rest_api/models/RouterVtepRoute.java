/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;

@ZoomClass(clazz = Topology.RouterVtepRoute.class)
public class RouterVtepRoute extends UriResource {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @NotNull
    @ZoomField(name = "binding_id", converter = UUIDUtil.Converter.class)
    public UUID bindingId;

    @NotNull
    @Pattern(regexp = IPv4Subnet.IPV4_CIDR_PATTERN, message = MessageProperty.IP_ADDR_INVALID)
    @ZoomField(name = "cidr", converter = IPSubnetUtil.Converter.class)
    public String cidr;

    @NotNull
    @Pattern(regexp = IPv4.regex, message = MessageProperty.IP_ADDR_INVALID)
    @ZoomField(name = "gw_ip", converter = IPAddressUtil.Converter.class)
    public String gwIp;

    @NotNull
    @Pattern(regexp = MAC.regex, message = MessageProperty.MAC_ADDRESS_INVALID)
    @ZoomField(name = "gw_mac")
    public String gwMac;

    @NotNull
    @Pattern(regexp = IPv4.regex, message = MessageProperty.IP_ADDR_INVALID)
    @ZoomField(name = "remote_vtep_ip", converter = IPAddressUtil.Converter.class)
    public String remoteVtepIp;

    @JsonIgnore
    @ZoomField(name = "route", converter = UUIDUtil.Converter.class)
    public UUID route;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.ROUTER_VTEP_ROUTES, id);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(RouterVtepRoute from) {
        this.id = from.id;
        route = from.route;
    }
}
