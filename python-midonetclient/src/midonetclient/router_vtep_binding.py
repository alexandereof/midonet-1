# Copyright (c) 2014 Midokura SARL, All Rights Reserved.
# All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

from midonetclient import resource_base
from midonetclient import vendor_media_type


class RouterVtepBinding(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_ROUTER_VTEP_BINDING_JSON

    def __init__(self, uri, dto, auth):
        super(RouterVtepBinding, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_vtep(self):
        return self.dto['vtepId']

    def vtep(self, val):
        self.dto['vtepId'] = val
        return self

    def get_vtep_ip(self):
        return self.dto['vtepTunnelIp']

    def vtep_ip(self, val):
        self.dto['vtepTunnelIp'] = val
        return self

    def get_router(self):
        return self.dto['routerId']

    def router(self, val):
        self.dto['routerId'] = val
        return self

    def get_vtep_port(self):
        return self.dto['vtepPortId']

    def get_router_port(self):
        return self.dto['routerPortId']

    def get_route1(self):
        return self.dto['route1']

    def get_route2(self):
        return self.dto['route2']

    def get_router_subnet(self):
        return self.dto['routerSubnet']

    def router_subnet(self, val):
        self.dto['routerSubnet'] = val
        return self

    def get_router_ip(self):
        return self.dto['routerIp']

    def router_ip(self, val):
        self.dto['routerIp'] = val
        return self

    def get_router_mac(self):
        return self.dto['routerMac']

    def router_mac(self, val):
        self.dto['routerMac'] = val
        return self

    def get_vni(self):
        return self.dto['vni']

    def vni(self, val):
        self.dto['vni'] = val
        return self
