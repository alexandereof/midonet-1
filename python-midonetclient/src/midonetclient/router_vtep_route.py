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


class RouterVtepRoute(resource_base.ResourceBase):

    media_type = vendor_media_type.APPLICATION_ROUTER_VTEP_ROUTE_JSON

    def __init__(self, uri, dto, auth):
        super(RouterVtepRoute, self).__init__(uri, dto, auth)

    def get_id(self):
        return self.dto['id']

    def get_binding(self):
        return self.dto['bindingId']

    def binding(self, val):
        self.dto['bindingId'] = val
        return self

    def get_cidr(self):
        return self.dto['cidr']

    def cidr(self, val):
        self.dto['cidr'] = val
        return self

    def get_gw_ip(self):
        return self.dto['gwIp']

    def gw_ip(self, val):
        self.dto['gwIp'] = val
        return self

    def get_gw_mac(self):
        return self.dto['gwMac']

    def gw_mac(self, val):
        self.dto['gwMac'] = val
        return self

    def get_remote_vtep_ip(self):
        return self.dto['remoteVtepIp']

    def remote_vtep_ip(self, val):
        self.dto['remoteVtepIp'] = val
        return self

    def get_route(self):
        return self.dto['route']
