/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.midolman.state.zkManagers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.common.base.Objects;

import org.apache.zookeeper.Op;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Arrays.asList;

/**
 * Class to manage the LoadBalancer ZooKeeper data.
 */
public class LoadBalancerZkManager extends
        AbstractZkManager<UUID, LoadBalancerZkManager.LoadBalancerConfig> {

    public static class LoadBalancerConfig extends BaseConfig {
        public UUID routerId;
        public boolean adminStateUp;

        public LoadBalancerConfig() {
            super();
        }

        public void setAdminStateUp(boolean adminStateUp) {
            this.adminStateUp = adminStateUp;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(routerId, adminStateUp);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || !getClass().equals(o.getClass()))
                return false;

            LoadBalancerConfig that = (LoadBalancerConfig) o;

            return adminStateUp == that.adminStateUp &&
                    Objects.equal(routerId, that.routerId);
        }
    }

    public LoadBalancerZkManager(ZkManager zk, PathBuilder paths,
                                 Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getLoadBalancerPath(id);
    }

    @Override
    protected Class<LoadBalancerConfig> getConfigClass() {
        return LoadBalancerConfig.class;
    }

    public Set<UUID> getPoolIds(UUID id) throws StateAccessException {
        return getUuidSet(paths.getLoadBalancerPoolsPath(id));
    }

    public Set<UUID> getVipIds(UUID id) throws StateAccessException {
        return getUuidSet(paths.getLoadBalancerVipsPath(id));
    }

    public List<Op> prepareSetRouterId(UUID id, UUID routerId)
            throws SerializationException, StateAccessException {
        LoadBalancerConfig config = get(id);
        config.routerId = routerId;
        return asList(simpleUpdateOp(id, config));
    }

    public void getVipIdListAsync(UUID loadBalancerId,
                                  final DirectoryCallback<Set<UUID>>
                                          vipContentsCallback,
                                  Directory.TypedWatcher watcher) {
        getUUIDSetAsync(paths.getLoadBalancerVipsPath(loadBalancerId),
                        vipContentsCallback, watcher);
    }

}
