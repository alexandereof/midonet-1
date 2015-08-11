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

package org.midonet.cluster.services.rest_api.resources.federation

import java.util.UUID

import javax.ws.rs.Path
import javax.ws.rs.core.MediaType.APPLICATION_JSON

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models.federation.{OvsdbBinding, VxlanSegment}
import org.midonet.cluster.services.rest_api.resources.MidonetResource
import org.midonet.cluster.services.rest_api.resources.MidonetResource._
import org.midonet.cluster.services.rest_api.resources.federation.FederationMediaTypes._

@ApiResource(version = 1)
@Path("ovsdb_bindings")
@RequestScoped
@AllowGet(Array(OVSDB_BINDING_JSON,
                APPLICATION_JSON))
@AllowList(Array(OVSDB_BINDING_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(OVSDB_BINDING_JSON,
                   APPLICATION_JSON))
@AllowDelete
class OvsdbBindingResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[OvsdbBinding](resContext) {

}

@RequestScoped
@AllowGet(Array(OVSDB_BINDING_JSON,
                APPLICATION_JSON))
@AllowList(Array(OVSDB_BINDING_COLLECTION_JSON,
                 APPLICATION_JSON))
class SegmentOvsdbBindingResource @Inject()(segmentId: UUID,
                                            resContext: ResourceContext)
    extends MidonetResource[OvsdbBinding](resContext) {

    protected override def listIds: Ids = {
        getResource(classOf[VxlanSegment], segmentId) map {
            _.ovsdbBindingIds.asScala
        }
    }
}
